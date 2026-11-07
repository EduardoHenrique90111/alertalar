package com.alertalar.integration.valhalla;

import com.alertalar.exception.IntegracaoExternaException;
import com.alertalar.integration.http.ClienteHttpExterno;
import com.alertalar.integration.http.JsonUtil;
import com.alertalar.integration.http.LimitadorIntervalo;
import com.alertalar.util.Texto;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cálculo de rotas com o Valhalla (motor de rotas aberto sobre o OpenStreetMap).
 * Por padrão usa o servidor público da FOSSGIS (valhalla1.openstreetmap.de), cuja política
 * pede User-Agent identificável, no máximo 1 requisição por segundo e uso moderado.
 * A URL é configurável (instância própria ou provedor compatível, como Stadia Maps).
 * Documentação da API: https://valhalla.github.io/valhalla/api/turn-by-turn/api-reference/
 */
public class ValhallaClient {

    public static final String FONTE = "Valhalla (FOSSGIS / OpenStreetMap)";
    private static final int PRECISAO_POLYLINE = 6;

    private final ClienteHttpExterno http;
    private final String urlBase;
    private final String chaveApi;
    private final LimitadorIntervalo limitador;

    public ValhallaClient(ClienteHttpExterno http, String urlBase, String chaveApi, LimitadorIntervalo limitador) {
        this.http = http;
        this.urlBase = urlBase.endsWith("/") ? urlBase.substring(0, urlBase.length() - 1) : urlBase;
        this.chaveApi = chaveApi;
        this.limitador = limitador;
    }

    /**
     * @param evitar polígonos (anéis de [lat, lon]) cujas vias o roteador deve evitar
     */
    public Rota rota(double latOrigem, double lonOrigem, double latDestino, double lonDestino, ModoRota modo,
                     List<List<double[]>> evitar) {
        Map<String, Object> corpo = new LinkedHashMap<>();
        corpo.put("locations", List.of(
                Map.of("lat", latOrigem, "lon", lonOrigem),
                Map.of("lat", latDestino, "lon", lonDestino)));
        corpo.put("costing", modo.getCosting());
        corpo.put("directions_options", Map.of("units", "kilometers", "language", "pt-BR"));
        if (evitar != null && !evitar.isEmpty()) {
            List<List<double[]>> aneis = new ArrayList<>();
            for (List<double[]> anel : evitar) {
                List<double[]> lonLat = new ArrayList<>(anel.size());
                for (double[] p : anel) {
                    lonLat.add(new double[]{p[1], p[0]}); // Valhalla usa [lon, lat]
                }
                aneis.add(lonLat);
            }
            corpo.put("exclude_polygons", aneis);
        }
        String url = urlBase + "/route" + (Texto.vazio(chaveApi) ? "" : "?api_key=" + ClienteHttpExterno.codificar(chaveApi));
        limitador.aguardarVez();
        JsonNode json = http.postJson(FONTE, URI.create(url), corpo);
        return interpretar(json);
    }

    static Rota interpretar(JsonNode json) {
        JsonNode trip = json.path("trip");
        JsonNode pernas = trip.path("legs");
        if (trip.isMissingNode() || !pernas.isArray() || pernas.isEmpty()) {
            String erro = JsonUtil.texto(json, "error");
            throw new IntegracaoExternaException(FONTE, "O serviço de rotas não encontrou um caminho"
                    + (erro == null ? "." : " (" + erro + ")."));
        }
        List<double[]> pontos = new ArrayList<>();
        List<Instrucao> instrucoes = new ArrayList<>();
        for (JsonNode perna : pernas.values()) {
            int deslocamento = pontos.size();
            List<double[]> trecho = Polyline.decodificar(JsonUtil.texto(perna, "shape"), PRECISAO_POLYLINE);
            pontos.addAll(trecho);
            for (JsonNode m : perna.path("maneuvers").values()) {
                Double km = JsonUtil.numero(m, "length");
                Double s = JsonUtil.numero(m, "time");
                Double indice = JsonUtil.numero(m, "begin_shape_index");
                instrucoes.add(new Instrucao(
                        JsonUtil.texto(m, "instruction"),
                        km == null ? 0 : km * 1000,
                        s == null ? 0 : s,
                        indice == null ? deslocamento : deslocamento + indice.intValue(),
                        m.path("type").asInt(0)));
            }
        }
        if (pontos.size() < 2) {
            throw new IntegracaoExternaException(FONTE, "O serviço de rotas retornou um trajeto vazio.");
        }
        Double km = JsonUtil.numero(trip.path("summary"), "length");
        Double segundos = JsonUtil.numero(trip.path("summary"), "time");
        return new Rota(pontos, instrucoes, km == null ? 0 : km * 1000, segundos == null ? 0 : segundos);
    }

    /** Trajeto calculado: pontos [lat, lon], instruções em português, distância (m) e duração (s). */
    public record Rota(List<double[]> pontos, List<Instrucao> instrucoes, double distanciaMetros, double duracaoSegundos) {
    }

    /** Uma manobra do trajeto ("Vire à direita na Rua X"). */
    public record Instrucao(String texto, double distanciaMetros, double duracaoSegundos, int indicePonto, int tipo) {
    }
}
