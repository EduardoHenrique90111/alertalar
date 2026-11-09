package com.alertalar.service;

import com.alertalar.dto.OcorrenciaAdminResponse;
import com.alertalar.dto.OcorrenciaRequest;
import com.alertalar.dto.OcorrenciaResponse;
import com.alertalar.dto.ValidacaoRequest;
import com.alertalar.entity.FonteDados;
import com.alertalar.entity.Ocorrencia;
import com.alertalar.entity.Usuario;
import com.alertalar.entity.enums.NivelLocalizacao;
import com.alertalar.entity.enums.OrigemOcorrencia;
import com.alertalar.entity.enums.PapelUsuario;
import com.alertalar.entity.enums.StatusIntegracao;
import com.alertalar.entity.enums.StatusValidacao;
import com.alertalar.entity.enums.TipoEvento;
import com.alertalar.entity.enums.TipoFonte;
import com.alertalar.exception.RegraNegocioException;
import com.alertalar.integration.ibge.IbgeLocalidadesClient;
import com.alertalar.repository.OcorrenciaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Registro de relatos e fluxo de moderação. */
@ExtendWith(MockitoExtension.class)
class OcorrenciaServiceTest {

    @Mock OcorrenciaRepository repository;
    @Mock FonteDadosService fontes;
    @Mock IbgeLocalidadesClient ibge;
    @Mock ColetaDadosService coleta;

    OcorrenciaService servico;
    final FonteDados fonteRelatos = new FonteDados("ALERTALAR_RELATOS", "Relatos", TipoFonte.RELATO_USUARIO, StatusIntegracao.ATIVA);

    @BeforeEach
    void preparar() {
        servico = new OcorrenciaService(repository, fontes, ibge, coleta);
        lenient().when(fontes.obrigatoria("ALERTALAR_RELATOS")).thenReturn(fonteRelatos);
        lenient().when(repository.save(any(Ocorrencia.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(ibge.codigoMunicipio("RS", "Canoas")).thenReturn(Optional.of("4304606"));
    }

    private static OcorrenciaRequest relato(Double lat, Double lon) {
        return new OcorrenciaRequest("Rua de Teste, 100", "Canoas", "rs", LocalDate.of(2024, 5, 4),
                "A água chegou na altura do joelho e entrou nas casas.", TipoEvento.INUNDACAO, lat, lon,
                "Maria", "maria@exemplo.com", true);
    }

    @Test
    @DisplayName("Relato entra como PENDENTE, de origem RELATO_USUARIO, com ponto quando há coordenadas")
    void registraPendente() {
        servico.registrarRelato(relato(-29.92, -51.18), null);
        ArgumentCaptor<Ocorrencia> captor = ArgumentCaptor.forClass(Ocorrencia.class);
        verify(repository).save(captor.capture());
        Ocorrencia o = captor.getValue();
        assertEquals(StatusValidacao.PENDENTE, o.getStatusValidacao());
        assertEquals(OrigemOcorrencia.RELATO_USUARIO, o.getOrigem());
        assertEquals(NivelLocalizacao.PONTO, o.getNivelLocalizacao());
        assertEquals("RS", o.getEstado());
        assertEquals("4304606", o.getCodigoIbge());
        assertEquals(fonteRelatos, o.getFonte());
    }

    @Test
    @DisplayName("Sem coordenadas, o relato fica no nível de município")
    void semCoordenadas() {
        servico.registrarRelato(relato(null, null), null);
        ArgumentCaptor<Ocorrencia> captor = ArgumentCaptor.forClass(Ocorrencia.class);
        verify(repository).save(captor.capture());
        assertEquals(NivelLocalizacao.MUNICIPIO, captor.getValue().getNivelLocalizacao());
    }

    @Test
    @DisplayName("Resposta pública não expõe nome nem contato do relator")
    void protegeDadosPessoais() {
        OcorrenciaResponse r = servico.registrarRelato(relato(-29.92, -51.18), null);
        String texto = r.toString();
        assertEquals(-1, texto.indexOf("maria@exemplo.com"));
        assertEquals(-1, texto.indexOf("Maria,"));
    }

    @Test
    @DisplayName("Administrador confirma relato: registra quem validou e quando")
    void confirma() {
        Ocorrencia o = new Ocorrencia();
        o.setOrigem(OrigemOcorrencia.RELATO_USUARIO);
        o.setStatusValidacao(StatusValidacao.PENDENTE);
        o.setTipoEvento(TipoEvento.ALAGAMENTO);
        o.setNivelLocalizacao(NivelLocalizacao.PONTO);
        o.setFonte(fonteRelatos);
        when(repository.findById(10L)).thenReturn(Optional.of(o));
        Usuario admin = new Usuario("Admin", "admin@exemplo.com", "hash", PapelUsuario.ADMIN);

        OcorrenciaAdminResponse r = servico.validar(10L, new ValidacaoRequest(StatusValidacao.CONFIRMADA, "Conferido com notícias locais"), admin);

        assertEquals("CONFIRMADA", r.statusValidacao());
        assertEquals(admin, o.getValidadoPor());
        assertNotNull(o.getDataValidacao());
    }

    @Test
    @DisplayName("Registros oficiais não passam por moderação")
    void oficialNaoModera() {
        Ocorrencia o = new Ocorrencia();
        o.setOrigem(OrigemOcorrencia.OFICIAL);
        when(repository.findById(5L)).thenReturn(Optional.of(o));
        assertThrows(RegraNegocioException.class, () -> servico.validar(5L, new ValidacaoRequest(StatusValidacao.REJEITADA, null), null));
    }

    @Test
    @DisplayName("Decisão PENDENTE não é aceita")
    void decisaoInvalida() {
        assertThrows(RegraNegocioException.class, () -> servico.validar(1L, new ValidacaoRequest(StatusValidacao.PENDENTE, null), null));
    }

    @Test
    @DisplayName("Coordenadas fora do Brasil são recusadas")
    void foraDoBrasil() {
        assertThrows(RegraNegocioException.class, () -> servico.registrarRelato(relato(40.0, -74.0), null));
    }

    @Test
    @DisplayName("Busca da moderação: texto em minúsculas, entre %, sem virar curinga")
    void padraoDaBusca() {
        assertEquals("%dom joão%", OcorrenciaService.padraoBusca("  Dom   João "));
        assertEquals("%100!% de água!_x!!%", OcorrenciaService.padraoBusca("100% de água_x!"));
        assertEquals(null, OcorrenciaService.padraoBusca("   "));
        assertEquals(null, OcorrenciaService.padraoBusca(null));
    }
}
