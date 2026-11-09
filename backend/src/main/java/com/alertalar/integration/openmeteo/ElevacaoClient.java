package com.alertalar.integration.openmeteo;

import com.alertalar.exception.IntegracaoExternaException;
import com.alertalar.integration.http.ClienteHttpExterno;
import com.alertalar.integration.http.JsonUtil;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Elevação do terreno pelo Copernicus DEM GLO-90 (resolução de 90 m), servido pela
 * Open-Meteo Elevation API (https://open-meteo.com/en/docs/elevation-api).
 * Aceita até 100 coordenadas por requisição.
 */
public class ElevacaoClient {

    public static final String FONTE = "Copernicus DEM GLO-90 (Open-Meteo)";
    private static final int MAXIMO_POR_REQUISICAO = 100;

    private final ClienteHttpExterno http;
    private final String url;

    public ElevacaoClient(ClienteHttpExterno http, String url) {
        this.http = http;
        this.url = url;
    }

    /**
     * @param pontos lista de [lat, lon]
     * @return elevações em metros, na mesma ordem (elemento nulo quando não houver dado)
     */
    public List<Double> elevacoes(List<double[]> pontos) {
        if (pontos == null || pontos.isEmpty()) {
            return List.of();
        }
        if (pontos.size() > MAXIMO_POR_REQUISICAO) {
            throw new IllegalArgumentException("Máximo de " + MAXIMO_POR_REQUISICAO + " pontos por requisição");
        }

        Map<String, Object> p = new LinkedHashMap<>();
        p.put("latitude", pontos.stream()
                .map(c -> String.format(Locale.ROOT, "%.6f", c[0]))
                .collect(Collectors.joining(",")));
        p.put("longitude", pontos.stream()
                .map(c -> String.format(Locale.ROOT, "%.6f", c[1]))
                .collect(Collectors.joining(",")));

        JsonNode json = http.getJson(FONTE, ClienteHttpExterno.uri(url, p));

        if (json.has("error") && json.path("error").asBoolean()) {
            throw new IntegracaoExternaException(FONTE, json.path("reason").asText("Erro reportado pela API Open-Meteo"));
        }

        JsonNode lista = json.path("elevation");
        if (!lista.isArray() || lista.size() != pontos.size()) {
            throw new IntegracaoExternaException(FONTE, "A fonte de elevação retornou uma quantidade inesperada de valores.");
        }

        List<Double> resultado = new ArrayList<>(pontos.size());
        for (JsonNode v : lista) {
            Double e = JsonUtil.numero(v);
            resultado.add(e == null || e.isNaN() ? null : e);
        }
        return resultado;
    }
}