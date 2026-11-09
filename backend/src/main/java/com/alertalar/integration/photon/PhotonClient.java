package com.alertalar.integration.photon;

import com.alertalar.integration.http.ClienteHttpExterno;
import com.alertalar.integration.http.JsonUtil;
import com.alertalar.integration.http.LimitadorIntervalo;
import com.alertalar.integration.nominatim.NominatimClient.ResultadoGeocodificacao;
import com.alertalar.util.UnidadeFederativa;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Geocodificação com o Photon (komoot), que busca nos mesmos dados do OpenStreetMap que o
 * Nominatim, em outro servidor. É a reserva quando o Nominatim público recusa pedidos
 * (limite por IP, comum em hospedagens compartilhadas) ou não responde.
 * Uso justo, sem chave: https://photon.komoot.io
 */
public class PhotonClient {

    public static final String FONTE = "Photon (OpenStreetMap)";

    /** Retângulo que contém o Brasil (oeste, sul, leste, norte), no formato do Photon. */
    static final String CAIXA_BRASIL = "-74.2,-34.0,-28.6,5.5";

    private final ClienteHttpExterno http;
    private final String urlBase;
    private final LimitadorIntervalo limitador;

    public PhotonClient(ClienteHttpExterno http, String urlBase, LimitadorIntervalo limitador) {
        this.http = http;
        this.urlBase = urlBase.endsWith("/") ? urlBase.substring(0, urlBase.length() - 1) : urlBase;
        this.limitador = limitador;
    }

    /** Busca por texto livre, só no Brasil. */
    public List<ResultadoGeocodificacao> buscar(String consulta, int limite) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("q", consulta);
        p.put("limit", Math.max(1, Math.min(limite, 15)));
        p.put("bbox", CAIXA_BRASIL);
        limitador.aguardarVez();
        JsonNode resposta = http.getJson(FONTE, ClienteHttpExterno.uri(urlBase + "/api", p));
        List<ResultadoGeocodificacao> resultados = new ArrayList<>();
        if (resposta == null) {
            return resultados;
        }
        for (JsonNode item : resposta.path("features").values()) {
            ResultadoGeocodificacao r = converter(item);
            if (r != null) {
                resultados.add(r);
            }
        }
        return resultados;
    }

    private static ResultadoGeocodificacao converter(JsonNode item) {
        JsonNode coordenadas = item.path("geometry").path("coordinates");
        JsonNode p = item.path("properties");
        if (!coordenadas.isArray() || coordenadas.size() < 2 || !"BR".equalsIgnoreCase(JsonUtil.texto(p, "countrycode"))) {
            return null;
        }
        double lon = coordenadas.get(0).asDouble();
        double lat = coordenadas.get(1).asDouble();
        String tipo = JsonUtil.texto(p, "type");
        String nome = JsonUtil.texto(p, "name");
        String rua = JsonUtil.texto(p, "street");
        if (rua == null && "street".equals(tipo)) {
            rua = nome; // o resultado é a própria rua
        }
        String cidade = JsonUtil.texto(p, "city");
        if (cidade == null && "city".equals(tipo)) {
            cidade = nome;
        }
        String bairro = JsonUtil.texto(p, "district");
        if (bairro == null) {
            bairro = JsonUtil.texto(p, "locality");
        }
        String estado = JsonUtil.texto(p, "state");
        String uf = UnidadeFederativa.deTexto(estado).map(Enum::name).orElse(null);
        String numero = JsonUtil.texto(p, "housenumber");
        String cep = JsonUtil.texto(p, "postcode");
        String nomeLugar = nome != null && !nome.equals(rua) ? nome : null;
        String exibicao = juntar(nomeLugar, numero, rua, bairro, cidade, estado, cep, "Brasil");
        return new ResultadoGeocodificacao(lat, lon, rank(tipo), JsonUtil.texto(p, "osm_value"),
                numero, rua, bairro, cidade, uf, cep, exibicao);
    }

    /**
     * Equivalente ao place_rank do Nominatim, para a mesma regra de precisão:
     * 30 = edificação, número ou lugar; 26 = rua; abaixo = bairro, cidade ou região.
     */
    static int rank(String tipo) {
        if (tipo == null) {
            return 18;
        }
        return switch (tipo) {
            case "house" -> 30;
            case "street" -> 26;
            case "locality", "district" -> 20;
            case "city" -> 16;
            case "county" -> 12;
            case "state" -> 8;
            case "country" -> 4;
            default -> 18;
        };
    }

    /** "Canoas Shopping, 6750, Avenida Guilherme Schell, Centro, Canoas, ..." (mesmo estilo do Nominatim). */
    private static String juntar(String... partes) {
        List<String> lista = new ArrayList<>();
        for (String parte : partes) {
            if (parte != null && !parte.isBlank() && (lista.isEmpty() || !Objects.equals(lista.get(lista.size() - 1), parte))) {
                lista.add(parte);
            }
        }
        return String.join(", ", lista);
    }
}
