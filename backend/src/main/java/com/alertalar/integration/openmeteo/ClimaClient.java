package com.alertalar.integration.openmeteo;

import com.alertalar.integration.http.ClienteHttpExterno;
import com.alertalar.integration.http.JsonUtil;
import tools.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Dados da SITUAÇÃO ATUAL (não usados na classificação de risco histórico):
 * chuva observada/prevista (Open-Meteo Forecast API) e vazão modelada de rios
 * (GloFAS via Open-Meteo Flood API).
 */
public class ClimaClient {

    public static final String FONTE_CHUVA = "Open-Meteo (previsão meteorológica)";
    public static final String FONTE_VAZAO = "GloFAS (Open-Meteo Flood API)";

    private final ClienteHttpExterno http;
    private final String urlPrevisao;
    private final String urlVazao;

    public ClimaClient(ClienteHttpExterno http, String urlPrevisao, String urlVazao) {
        this.http = http;
        this.urlPrevisao = urlPrevisao;
        this.urlVazao = urlVazao;
    }

    public SerieChuva chuvaHoraria(double lat, double lon) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("latitude", String.format(Locale.ROOT, "%.5f", lat));
        p.put("longitude", String.format(Locale.ROOT, "%.5f", lon));
        p.put("hourly", "precipitation");
        p.put("past_days", 1);
        p.put("forecast_days", 3);
        p.put("timezone", "auto");
        JsonNode json = http.getJson(FONTE_CHUVA, ClienteHttpExterno.uri(urlPrevisao, p));
        int offsetSeg = json.path("utc_offset_seconds").asInt(0);
        List<LocalDateTime> horas = new ArrayList<>();
        List<Double> valores = new ArrayList<>();
        JsonNode tempos = json.path("hourly").path("time");
        JsonNode chuva = json.path("hourly").path("precipitation");
        for (int i = 0; i < tempos.size() && i < chuva.size(); i++) {
            horas.add(LocalDateTime.parse(tempos.get(i).asString()));
            valores.add(JsonUtil.numero(chuva.get(i)));
        }
        return new SerieChuva(ZoneOffset.ofTotalSeconds(offsetSeg), horas, valores);
    }

    public SerieVazao vazaoDiaria(double lat, double lon) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("latitude", String.format(Locale.ROOT, "%.5f", lat));
        p.put("longitude", String.format(Locale.ROOT, "%.5f", lon));
        p.put("daily", "river_discharge");
        p.put("past_days", 7);
        p.put("forecast_days", 7);
        JsonNode json = http.getJson(FONTE_VAZAO, ClienteHttpExterno.uri(urlVazao, p));
        List<LocalDate> dias = new ArrayList<>();
        List<Double> valores = new ArrayList<>();
        JsonNode tempos = json.path("daily").path("time");
        JsonNode vazao = json.path("daily").path("river_discharge");
        for (int i = 0; i < tempos.size() && i < vazao.size(); i++) {
            dias.add(LocalDate.parse(tempos.get(i).asString()));
            valores.add(JsonUtil.numero(vazao.get(i)));
        }
        return new SerieVazao(dias, valores);
    }

    /** Série horária de chuva (mm) no horário local do ponto. */
    public record SerieChuva(ZoneOffset fuso, List<LocalDateTime> horas, List<Double> milimetros) {
    }

    /** Série diária de vazão (m³/s). */
    public record SerieVazao(List<LocalDate> dias, List<Double> metrosCubicosPorSegundo) {
    }
}
