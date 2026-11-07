package com.alertalar.service;

import com.alertalar.dto.SituacaoAtualResponse;
import com.alertalar.entity.Consulta;
import com.alertalar.entity.FatorConsulta;
import com.alertalar.entity.enums.ClassificacaoRisco;
import com.alertalar.exception.IntegracaoExternaException;
import com.alertalar.integration.openmeteo.ClimaClient;
import com.alertalar.integration.openmeteo.ClimaClient.SerieChuva;
import com.alertalar.repository.ConsultaRepository;
import com.alertalar.service.analise.fatores.FatorProximidadeAgua;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.IntToDoubleFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Chuva das últimas 72 h e das próximas 24 h, e o risco de agora a partir da consulta exibida. */
@ExtendWith(MockitoExtension.class)
class SituacaoAtualServiceTest {

    private static final double LAT = -29.92;
    private static final double LON = -51.18;
    /** 07/11/2026 12:00 em Brasília (15:00 UTC) */
    private static final Instant AGORA = Instant.parse("2026-11-07T15:00:00Z");
    private static final ZoneOffset FUSO = ZoneOffset.ofHours(-3);

    @Mock ClimaClient clima;
    @Mock FonteDadosService fontes;
    @Mock ConsultaRepository consultas;

    private SituacaoAtualService service;

    @BeforeEach
    void criar() {
        service = new SituacaoAtualService(clima, fontes, consultas, Clock.fixed(AGORA, ZoneOffset.UTC));
    }

    /** Série horária de 72 h atrás até 96 h à frente; a função recebe a hora relativa a agora. */
    private static SerieChuva serie(IntToDoubleFunction mmPorHoraRelativa) {
        LocalDateTime agora = LocalDateTime.ofInstant(AGORA, FUSO);
        List<LocalDateTime> horas = new ArrayList<>();
        List<Double> mm = new ArrayList<>();
        for (int h = -80; h <= 96; h++) {
            horas.add(agora.plusHours(h));
            mm.add(mmPorHoraRelativa.applyAsDouble(h));
        }
        return new SerieChuva(FUSO, horas, mm);
    }

    private static Consulta consulta(ClassificacaoRisco classificacao, int pontuacaoAgua) {
        Consulta c = new Consulta();
        c.setClassificacao(classificacao);
        FatorConsulta f = new FatorConsulta();
        f.setCodigo(FatorProximidadeAgua.CODIGO);
        f.setPontuacao(pontuacaoAgua);
        c.adicionarFator(f);
        return c;
    }

    @Test
    @DisplayName("Soma a chuva por janela: 72 h e 24 h passadas, próximas 24 h e dias seguintes")
    void janelas() {
        // 1 mm/h entre 72 e 25 h atrás; 2 mm/h nas últimas 24 h; 3 mm/h nas próximas 24 h; 0,5 mm/h depois
        when(clima.chuvaHoraria(anyDouble(), anyDouble())).thenReturn(serie(h -> h <= -72 ? 9 : h <= -24 ? 1 : h <= 0 ? 2 : h <= 24 ? 3 : 0.5));
        when(clima.vazaoDiaria(anyDouble(), anyDouble())).thenThrow(new IntegracaoExternaException("GloFAS", "sem rio"));

        SituacaoAtualResponse s = service.consultar(LAT, LON);

        assertEquals(48.0, s.chuva().ultimas24hMm());
        assertEquals(48.0 + 48.0, s.chuva().ultimas72hMm());
        assertEquals(2.0, s.chuva().maiorIntensidadeHorariaUltimas24hMm());
        assertEquals(72.0, s.chuva().proximas24hMm());
        assertEquals(3.0, s.chuva().maiorIntensidadeHorariaProximas24hMm());
        assertEquals(12.0, s.chuva().maior24hEntre24e72hMm());
        assertEquals(72.0 + 24.0, s.chuva().proximas72hMm());
        assertFalse(s.vazao().disponivel());
        assertEquals(2, s.riscoAgora().nivelChuva(), "72 mm previstos em 24 h = chuva forte");
        assertEquals("ALTO", s.riscoAgora().nivel(), "sem consulta, suscetibilidade moderada");
    }

    @Test
    @DisplayName("Com a consulta da tela: local de alta suscetibilidade e sem chuva fica 'baixo agora'")
    void consultaAltoSemChuva() {
        UUID id = UUID.randomUUID();
        when(clima.chuvaHoraria(anyDouble(), anyDouble())).thenReturn(serie(h -> 0));
        when(clima.vazaoDiaria(anyDouble(), anyDouble())).thenThrow(new IntegracaoExternaException("GloFAS", "sem rio"));
        when(consultas.findWithFatoresById(id)).thenReturn(Optional.of(consulta(ClassificacaoRisco.ALTO, 3)));

        SituacaoAtualResponse s = service.consultar(LAT, LON, id);

        assertEquals("BAIXO", s.riscoAgora().nivel());
        assertEquals("ALTO", s.riscoAgora().suscetibilidade());
    }

    @Test
    @DisplayName("Mesma chuva, locais diferentes: a suscetibilidade da consulta muda o risco agora; a chuva vem do cache")
    void suscetibilidadeMudaRisco() {
        UUID alto = UUID.randomUUID();
        UUID baixo = UUID.randomUUID();
        when(clima.chuvaHoraria(anyDouble(), anyDouble())).thenReturn(serie(h -> h > -24 && h <= 0 ? 1.5 : 0)); // 36 mm em 24 h
        when(clima.vazaoDiaria(anyDouble(), anyDouble())).thenThrow(new IntegracaoExternaException("GloFAS", "sem rio"));
        when(consultas.findWithFatoresById(alto)).thenReturn(Optional.of(consulta(ClassificacaoRisco.ALTO, 0)));
        when(consultas.findWithFatoresById(baixo)).thenReturn(Optional.of(consulta(ClassificacaoRisco.BAIXO, 0)));

        assertEquals("ALTO", service.consultar(LAT, LON, alto).riscoAgora().nivel());
        assertEquals("BAIXO", service.consultar(LAT, LON, baixo).riscoAgora().nivel());
        assertEquals("ATENCAO", service.consultar(LAT, LON, UUID.randomUUID()).riscoAgora().nivel(), "consulta inexistente = moderada");
        verify(clima, times(1)).chuvaHoraria(anyDouble(), anyDouble());
    }

    @Test
    @DisplayName("Fonte de chuva fora do ar: risco agora indisponível")
    void chuvaIndisponivel() {
        when(clima.chuvaHoraria(anyDouble(), anyDouble())).thenThrow(new IntegracaoExternaException("Open-Meteo", "fora do ar"));
        when(clima.vazaoDiaria(anyDouble(), anyDouble())).thenThrow(new IntegracaoExternaException("GloFAS", "fora do ar"));
        SituacaoAtualResponse s = service.consultar(LAT, LON);
        assertFalse(s.chuva().disponivel());
        assertEquals("INDISPONIVEL", s.riscoAgora().nivel());
        assertNull(s.chuva().ultimas72hMm());
    }
}
