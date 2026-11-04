package com.alertalar.integration.nominatim;

import com.alertalar.integration.http.ClienteHttpExterno;
import com.alertalar.integration.http.JsonUtil;
import com.alertalar.integration.http.LimitadorIntervalo;
import com.alertalar.util.Texto;
import com.alertalar.util.UnidadeFederativa;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Geocodificação com o Nominatim (OpenStreetMap). Respeita a política de uso do serviço
 * público: User-Agent identificável, no máximo 1 requisição/segundo e cache dos resultados
 * (o cache persistente fica na tabela endereco).
 * Política: https://operations.osmfoundation.org/policies/nominatim/
 */
public class NominatimClient {

    public static final String FONTE = "Nominatim (OpenStreetMap)";

    private final ClienteHttpExterno http;
    private final String urlBase;
    private final String emailContato;
    private final LimitadorIntervalo limitador;

    public NominatimClient(ClienteHttpExterno http, String urlBase, String emailContato, LimitadorIntervalo limitador) {
        this.http = http;
        this.urlBase = urlBase.endsWith("/") ? urlBase.substring(0, urlBase.length() - 1) : urlBase;
        this.emailContato = emailContato;
        this.limitador = limitador;
    }

    /** Busca estruturada (mais precisa que texto livre). */
    public List<ResultadoGeocodificacao> buscarEstruturado(String rua, String numero, String cidade, String uf, int limite) {
        Map<String, Object> p = parametrosBase(limite);
        String street = Texto.vazio(numero) ? rua : numero.trim() + " " + rua.trim();
        p.put("street", street);
        p.put("city", cidade);
        UnidadeFederativa.daSigla(uf).ifPresent(u -> p.put("state", u.getNome()));
        p.put("country", "Brasil");
        return executar(p);
    }

    /** Busca por texto livre (usada como segunda tentativa). */
    public List<ResultadoGeocodificacao> buscarTexto(String consulta, int limite) {
        Map<String, Object> p = parametrosBase(limite);
        p.put("q", consulta);
        return executar(p);
    }

    private Map<String, Object> parametrosBase(int limite) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("format", "jsonv2");
        p.put("addressdetails", 1);
        p.put("countrycodes", "br");
        p.put("limit", Math.max(1, Math.min(limite, 10)));
        p.put("accept-language", "pt-BR");
        if (!Texto.vazio(emailContato)) {
            p.put("email", emailContato);
        }
        return p;
    }

    private List<ResultadoGeocodificacao> executar(Map<String, Object> parametros) {
        limitador.aguardarVez();
        JsonNode resposta = http.getJson(FONTE, ClienteHttpExterno.uri(urlBase + "/search", parametros));
        List<ResultadoGeocodificacao> resultados = new ArrayList<>();
        if (resposta == null || !resposta.isArray()) {
            return resultados;
        }
        for (JsonNode item : resposta.values()) {
            Double lat = JsonUtil.numero(item, "lat");
            Double lon = JsonUtil.numero(item, "lon");
            if (lat == null || lon == null) {
                continue;
            }
            JsonNode end = item.path("address");
            Double rank = JsonUtil.numero(item, "place_rank");
            String bairro = primeiro(end, "suburb", "neighbourhood", "quarter", "city_district", "borough");
            String cidade = primeiro(end, "city", "town", "village", "municipality", "hamlet");
            String iso = JsonUtil.texto(end, "ISO3166-2-lvl4");
            String uf = iso != null && iso.startsWith("BR-") ? iso.substring(3) : null;
            if (uf == null) {
                uf = UnidadeFederativa.deTexto(JsonUtil.texto(end, "state")).map(Enum::name).orElse(null);
            }
            resultados.add(new ResultadoGeocodificacao(
                    lat, lon,
                    rank == null ? 0 : rank.intValue(),
                    JsonUtil.texto(item, "addresstype"),
                    JsonUtil.texto(end, "house_number"),
                    primeiro(end, "road", "pedestrian", "footway", "residential"),
                    bairro, cidade, uf,
                    JsonUtil.texto(end, "postcode"),
                    JsonUtil.texto(item, "display_name")));
        }
        return resultados;
    }

    private static String primeiro(JsonNode no, String... campos) {
        for (String c : campos) {
            String v = JsonUtil.texto(no, c);
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    /**
     * Resultado do Nominatim. {@code placeRank}: 30 = edificação/número; 26-27 = rua;
     * 16-25 = bairro/localidade; abaixo disso = cidade ou região.
     */
    public record ResultadoGeocodificacao(double latitude, double longitude, int placeRank, String tipoEndereco,
                                          String numero, String rua, String bairro, String cidade, String uf,
                                          String cep, String nomeExibicao) {
    }
}
