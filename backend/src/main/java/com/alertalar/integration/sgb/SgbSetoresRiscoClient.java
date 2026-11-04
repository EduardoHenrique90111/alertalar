package com.alertalar.integration.sgb;

import com.alertalar.exception.IntegracaoExternaException;
import com.alertalar.integration.http.ClienteHttpExterno;
import com.alertalar.integration.http.JsonUtil;
import com.alertalar.util.Texto;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;

/**
 * Setores de risco geológico/hidrológico mapeados pelo Serviço Geológico do Brasil (SGB),
 * consultados no serviço ArcGIS REST público do geoportal do SGB (operação "query").
 * Campos conforme o serviço "Setorização de Risco": uf, munic, num_setor, local,
 * tipolo_g1..5, tipolo_e1..5, cobrade_01..05, descricao, grau_risco, data_setor.
 */
public class SgbSetoresRiscoClient {

    public static final String FONTE = "Serviço Geológico do Brasil - Setorização de Riscos";

    private final ClienteHttpExterno http;
    private final String urlCamada;

    public SgbSetoresRiscoClient(ClienteHttpExterno http, String urlCamada) {
        this.http = http;
        this.urlCamada = urlCamada.endsWith("/") ? urlCamada.substring(0, urlCamada.length() - 1) : urlCamada;
    }

    public List<SetorRisco> buscarProximos(double lat, double lon, int raioMetros) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("geometry", String.format(Locale.ROOT, "%.6f,%.6f", lon, lat));
        p.put("geometryType", "esriGeometryPoint");
        p.put("inSR", 4326);
        p.put("spatialRel", "esriSpatialRelIntersects");
        p.put("distance", raioMetros);
        p.put("units", "esriSRUnit_Meter");
        p.put("outFields", "*");
        p.put("returnGeometry", "true");
        p.put("outSR", 4326);
        p.put("f", "json");
        JsonNode json = http.getJson(FONTE, ClienteHttpExterno.uri(urlCamada + "/query", p));
        // O ArcGIS pode responder HTTP 200 com um objeto "error"
        if (json.has("error")) {
            String msg = JsonUtil.texto(json.path("error"), "message");
            throw new IntegracaoExternaException(FONTE, "O serviço do SGB recusou a consulta" + (msg == null ? "." : ": " + msg));
        }
        List<SetorRisco> setores = new ArrayList<>();
        for (JsonNode f : json.path("features").values()) {
            setores.add(interpretar(f));
        }
        return setores;
    }

    static SetorRisco interpretar(JsonNode feature) {
        Map<String, JsonNode> atributos = new LinkedHashMap<>();
        JsonNode attrs = feature.path("attributes");
        for (String nome : attrs.propertyNames()) {
            // Alguns serviços prefixam o nome do campo com a tabela (ex.: "gestao.risco.uf")
            String curto = nome.contains(".") ? nome.substring(nome.lastIndexOf('.') + 1) : nome;
            atributos.put(curto.toLowerCase(Locale.ROOT), attrs.get(nome));
        }
        StringJoiner tipologias = new StringJoiner("; ");
        StringBuilder textoBusca = new StringBuilder();
        for (int i = 1; i <= 5; i++) {
            String g = texto(atributos, "tipolo_g" + i);
            String e = texto(atributos, "tipolo_e" + i);
            String c = texto(atributos, String.format("cobrade_%02d", i));
            if (e != null || g != null) {
                tipologias.add(e != null ? e : g);
            }
            textoBusca.append(' ').append(g).append(' ').append(e).append(' ').append(c);
        }
        String descricao = texto(atributos, "descricao");
        textoBusca.append(' ').append(descricao);

        Double dataMillis = JsonUtil.numero(atributos.get("data_setor"));
        LocalDate data = dataMillis == null ? null
                : Instant.ofEpochMilli(dataMillis.longValue()).atZone(ZoneOffset.UTC).toLocalDate();

        List<List<double[]>> aneis = new ArrayList<>();
        for (JsonNode anel : feature.path("geometry").path("rings").values()) {
            List<double[]> pontos = new ArrayList<>();
            for (JsonNode xy : anel.values()) {
                Double x = JsonUtil.numero(xy.path(0));
                Double y = JsonUtil.numero(xy.path(1));
                if (x != null && y != null) {
                    pontos.add(new double[]{y, x}); // ArcGIS usa [x=lon, y=lat]
                }
            }
            if (pontos.size() >= 3) {
                aneis.add(pontos);
            }
        }
        return new SetorRisco(
                texto(atributos, "munic"),
                texto(atributos, "uf"),
                texto(atributos, "num_setor"),
                texto(atributos, "local"),
                tipologias.length() == 0 ? null : tipologias.toString(),
                texto(atributos, "grau_risco"),
                descricao,
                data,
                ehHidrologico(textoBusca.toString()),
                aneis);
    }

    /** Inundação, enxurrada e alagamento (COBRADE 1.2.x - desastres hidrológicos). */
    static boolean ehHidrologico(String texto) {
        String n = Texto.normalizar(texto);
        return n.contains("inunda") || n.contains("enxurr") || n.contains("alaga") || n.contains("hidrolog")
                || n.matches(".*\\b1\\.2\\.[123].*");
    }

    private static String texto(Map<String, JsonNode> atributos, String campo) {
        JsonNode v = atributos.get(campo);
        if (v == null || v.isNull() || v.isMissingNode()) {
            return null;
        }
        String s = v.isString() ? v.stringValue() : v.asString();
        return s == null || s.isBlank() ? null : s.trim();
    }

    /** Setor de risco do SGB; anéis em [lat, lon]. */
    public record SetorRisco(String municipio, String uf, String numeroSetor, String local, String tipologia,
                             String grauRisco, String descricao, LocalDate dataSetorizacao, boolean hidrologico,
                             List<List<double[]>> aneis) {
    }
}
