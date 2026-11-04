package com.alertalar.service;

import com.alertalar.dto.SituacaoAtualResponse;
import com.alertalar.dto.SituacaoAtualResponse.Chuva;
import com.alertalar.dto.SituacaoAtualResponse.Vazao;
import com.alertalar.exception.RegraNegocioException;
import com.alertalar.integration.http.CacheTemporario;
import com.alertalar.integration.openmeteo.ClimaClient;
import com.alertalar.integration.openmeteo.ClimaClient.SerieChuva;
import com.alertalar.integration.openmeteo.ClimaClient.SerieVazao;
import com.alertalar.service.analise.Dado;
import com.alertalar.util.Geo;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Situação atual (chuva recente/prevista e vazão modelada). Mantida separada da
 * classificação de risco histórico, conforme a metodologia.
 */
@Service
public class SituacaoAtualService {

    private final ClimaClient clima;
    private final FonteDadosService fontes;
    private final Clock relogio;
    private final CacheTemporario<String, SituacaoAtualResponse> cache = new CacheTemporario<>(Duration.ofMinutes(15), 2_000);

    @org.springframework.beans.factory.annotation.Autowired
    public SituacaoAtualService(ClimaClient clima, FonteDadosService fontes) {
        this(clima, fontes, Clock.systemUTC());
    }

    SituacaoAtualService(ClimaClient clima, FonteDadosService fontes, Clock relogio) {
        this.clima = clima;
        this.fontes = fontes;
        this.relogio = relogio;
    }

    public SituacaoAtualResponse consultar(double lat, double lon) {
        if (!Geo.dentroDoBrasil(lat, lon)) {
            throw new RegraNegocioException("As coordenadas informadas estão fora do território brasileiro.");
        }
        // Grade de ~1 km: os modelos meteorológicos têm resolução mais grossa que isso
        String chave = String.format(Locale.ROOT, "%.2f,%.2f", lat, lon);
        return cache.obterOuCalcular(chave, () -> calcular(lat, lon));
    }

    SituacaoAtualResponse calcular(double lat, double lon) {
        Dado<SerieChuva> chuva = Dado.coletar(() -> clima.chuvaHoraria(lat, lon));
        Dado<SerieVazao> vazao = Dado.coletar(() -> clima.vazaoDiaria(lat, lon));
        if (chuva.disponivel()) fontes.registrarAcesso("OPEN_METEO_PREVISAO", chuva.obtidoEm());
        if (vazao.disponivel()) fontes.registrarAcesso("OPEN_METEO_GLOFAS", vazao.obtidoEm());
        return new SituacaoAtualResponse(lat, lon, resumirChuva(chuva), resumirVazao(vazao), avisos(), Instant.now(relogio));
    }

    Chuva resumirChuva(Dado<SerieChuva> dado) {
        if (!dado.disponivel()) {
            return new Chuva(false, dado.motivoIndisponibilidade(), null, null, null, null, ClimaClient.FONTE_CHUVA);
        }
        SerieChuva s = dado.valor();
        LocalDateTime agora = LocalDateTime.ofInstant(Instant.now(relogio), s.fuso());
        double passado24 = 0, futuro24 = 0, futuro72 = 0, maxHora = 0;
        boolean algum = false;
        for (int i = 0; i < s.horas().size(); i++) {
            Double mm = s.milimetros().get(i);
            if (mm == null) continue;
            algum = true;
            LocalDateTime h = s.horas().get(i);
            if (!h.isAfter(agora) && h.isAfter(agora.minusHours(24))) {
                passado24 += mm;
            } else if (h.isAfter(agora)) {
                if (!h.isAfter(agora.plusHours(24))) {
                    futuro24 += mm;
                    maxHora = Math.max(maxHora, mm);
                }
                if (!h.isAfter(agora.plusHours(72))) {
                    futuro72 += mm;
                }
            }
        }
        if (!algum) {
            return new Chuva(false, "A fonte não retornou dados de chuva para este ponto.", null, null, null, null, ClimaClient.FONTE_CHUVA);
        }
        return new Chuva(true, null, arred(passado24), arred(futuro24), arred(futuro72), arred(maxHora), ClimaClient.FONTE_CHUVA);
    }

    Vazao resumirVazao(Dado<SerieVazao> dado) {
        String observacao = "Vazão diária simulada pelo GloFAS numa grade de ~5 km. Representa o rio principal mais "
                + "próximo da grade e pode não refletir córregos ou rios pequenos.";
        if (!dado.disponivel()) {
            return new Vazao(false, dado.motivoIndisponibilidade(), null, null, null, null, ClimaClient.FONTE_VAZAO, observacao);
        }
        SerieVazao s = dado.valor();
        LocalDate hoje = LocalDate.now(relogio);
        Double valorHoje = null, seteDias = null, maxPrev = null;
        for (int i = 0; i < s.dias().size(); i++) {
            LocalDate d = s.dias().get(i);
            Double v = s.metrosCubicosPorSegundo().get(i);
            if (v == null) continue;
            if (d.equals(hoje)) valorHoje = v;
            if (d.equals(hoje.minusDays(7))) seteDias = v;
            if (d.isAfter(hoje)) maxPrev = maxPrev == null ? v : Math.max(maxPrev, v);
        }
        boolean semDados = s.metrosCubicosPorSegundo().stream().allMatch(Objects::isNull);
        if (semDados || valorHoje == null) {
            return new Vazao(false, "Não há rio modelado pelo GloFAS para este ponto.", null, null, null, null,
                    ClimaClient.FONTE_VAZAO, observacao);
        }
        String tendencia = null;
        if (maxPrev != null) {
            double variacao = (maxPrev - valorHoje) / Math.max(valorHoje, 0.001);
            tendencia = variacao > 0.2 ? "SUBINDO" : variacao < -0.2 ? "DESCENDO" : "ESTAVEL";
        }
        return new Vazao(true, null, arred(valorHoje), seteDias == null ? null : arred(seteDias),
                maxPrev == null ? null : arred(maxPrev), tendencia, ClimaClient.FONTE_VAZAO, observacao);
    }

    private static List<String> avisos() {
        return List.of(
                "Dados de modelos meteorológicos e hidrológicos: não substituem os alertas oficiais.",
                "Para receber alertas oficiais da Defesa Civil por SMS, envie o seu CEP para 40199.",
                "Em emergência ligue 199 (Defesa Civil) ou 193 (Corpo de Bombeiros).");
    }

    private static double arred(double v) {
        return Math.round(v * 10) / 10.0;
    }
}
