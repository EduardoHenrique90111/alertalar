package com.alertalar.service;

import com.alertalar.dto.SituacaoAtualResponse;
import com.alertalar.dto.SituacaoAtualResponse.Chuva;
import com.alertalar.dto.SituacaoAtualResponse.RiscoAgora;
import com.alertalar.dto.SituacaoAtualResponse.Vazao;
import com.alertalar.entity.Consulta;
import com.alertalar.entity.enums.ClassificacaoRisco;
import com.alertalar.exception.RegraNegocioException;
import com.alertalar.integration.http.CacheTemporario;
import com.alertalar.integration.openmeteo.ClimaClient;
import com.alertalar.integration.openmeteo.ClimaClient.SerieChuva;
import com.alertalar.integration.openmeteo.ClimaClient.SerieVazao;
import com.alertalar.repository.ConsultaRepository;
import com.alertalar.service.analise.Dado;
import com.alertalar.service.analise.RiscoAgoraAvaliador;
import com.alertalar.service.analise.fatores.FatorProximidadeAgua;
import com.alertalar.util.Geo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Situação atual (chuva recente/prevista e vazão modelada) e o risco deste momento, que
 * combina essas medidas com a suscetibilidade do local ({@link RiscoAgoraAvaliador}). A
 * classificação gravada na consulta não muda.
 */
@Service
public class SituacaoAtualService {

    private final ClimaClient clima;
    private final FonteDadosService fontes;
    private final ConsultaRepository consultas;
    private final Clock relogio;
    private final CacheTemporario<String, Medidas> cache = new CacheTemporario<>(Duration.ofMinutes(15), 2_000);

    @org.springframework.beans.factory.annotation.Autowired
    public SituacaoAtualService(ClimaClient clima, FonteDadosService fontes, ConsultaRepository consultas) {
        this(clima, fontes, consultas, Clock.systemUTC());
    }

    SituacaoAtualService(ClimaClient clima, FonteDadosService fontes, ConsultaRepository consultas, Clock relogio) {
        this.clima = clima;
        this.fontes = fontes;
        this.consultas = consultas;
        this.relogio = relogio;
    }

    /** Chuva e vazão medidas para um ponto (guardadas 15 min por grade de ~1 km). */
    record Medidas(Chuva chuva, Vazao vazao, Instant consultadoEm) {
    }

    public SituacaoAtualResponse consultar(double lat, double lon) {
        return consultar(lat, lon, null);
    }

    /**
     * @param idConsulta consulta cujo resultado está na tela (opcional): sua classificação e a
     *                   proximidade de água entram no risco de agora
     */
    @Transactional(readOnly = true)
    public SituacaoAtualResponse consultar(double lat, double lon, UUID idConsulta) {
        if (!Geo.dentroDoBrasil(lat, lon)) {
            throw new RegraNegocioException("As coordenadas informadas estão fora do território brasileiro.");
        }
        // Grade de ~1 km: os modelos meteorológicos têm resolução mais grossa que isso
        String chave = String.format(Locale.ROOT, "%.2f,%.2f", lat, lon);
        Medidas m = cache.obterOuCalcular(chave, () -> medir(lat, lon));

        ClassificacaoRisco suscetibilidade = null;
        boolean pertoDeAgua = false;
        Optional<Consulta> consulta = idConsulta == null ? Optional.empty() : consultas.findWithFatoresById(idConsulta);
        if (consulta.isPresent()) {
            suscetibilidade = consulta.get().getClassificacao();
            pertoDeAgua = consulta.get().getFatores().stream()
                    .anyMatch(f -> FatorProximidadeAgua.CODIGO.equals(f.getCodigo())
                            && f.getPontuacao() != null && f.getPontuacao() >= 2);
        }
        RiscoAgora risco = RiscoAgoraAvaliador.avaliar(m.chuva(), m.vazao(), suscetibilidade, pertoDeAgua);
        return new SituacaoAtualResponse(lat, lon, risco, m.chuva(), m.vazao(), avisos(), m.consultadoEm());
    }

    Medidas medir(double lat, double lon) {
        Dado<SerieChuva> chuva = Dado.coletar(() -> clima.chuvaHoraria(lat, lon));
        Dado<SerieVazao> vazao = Dado.coletar(() -> clima.vazaoDiaria(lat, lon));
        if (chuva.disponivel()) fontes.registrarAcesso("OPEN_METEO_PREVISAO", chuva.obtidoEm());
        if (vazao.disponivel()) fontes.registrarAcesso("OPEN_METEO_GLOFAS", vazao.obtidoEm());
        return new Medidas(resumirChuva(chuva), resumirVazao(vazao), Instant.now(relogio));
    }

    Chuva resumirChuva(Dado<SerieChuva> dado) {
        if (!dado.disponivel()) {
            return Chuva.indisponivel(dado.motivoIndisponibilidade(), ClimaClient.FONTE_CHUVA);
        }
        SerieChuva s = dado.valor();
        LocalDateTime agora = LocalDateTime.ofInstant(Instant.now(relogio), s.fuso());
        double passado24 = 0, passado72 = 0, maxHoraPassada = 0;
        double futuro24 = 0, futuro72 = 0, maxHoraFutura = 0, dia2 = 0, dia3 = 0;
        boolean algum = false;
        for (int i = 0; i < s.horas().size(); i++) {
            Double mm = s.milimetros().get(i);
            if (mm == null) continue;
            algum = true;
            LocalDateTime h = s.horas().get(i);
            if (!h.isAfter(agora)) {
                if (h.isAfter(agora.minusHours(72))) {
                    passado72 += mm;
                }
                if (h.isAfter(agora.minusHours(24))) {
                    passado24 += mm;
                    maxHoraPassada = Math.max(maxHoraPassada, mm);
                }
            } else if (!h.isAfter(agora.plusHours(24))) {
                futuro24 += mm;
                futuro72 += mm;
                maxHoraFutura = Math.max(maxHoraFutura, mm);
            } else if (!h.isAfter(agora.plusHours(48))) {
                dia2 += mm;
                futuro72 += mm;
            } else if (!h.isAfter(agora.plusHours(72))) {
                dia3 += mm;
                futuro72 += mm;
            }
        }
        if (!algum) {
            return Chuva.indisponivel("A fonte não retornou dados de chuva para este ponto.", ClimaClient.FONTE_CHUVA);
        }
        return new Chuva(true, null, arred(passado24), arred(passado72), arred(maxHoraPassada), arred(futuro24),
                arred(futuro72), arred(maxHoraFutura), arred(Math.max(dia2, dia3)), ClimaClient.FONTE_CHUVA);
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
