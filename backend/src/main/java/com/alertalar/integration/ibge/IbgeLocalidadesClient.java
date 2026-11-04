package com.alertalar.integration.ibge;

import com.alertalar.integration.http.CacheTemporario;
import com.alertalar.integration.http.ClienteHttpExterno;
import com.alertalar.integration.http.JsonUtil;
import com.alertalar.util.Texto;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * API de Localidades do IBGE: obtém o código IBGE (7 dígitos) do município, usado para
 * cruzar o endereço com registros oficiais municipais.
 * Documentação: https://servicodados.ibge.gov.br/api/docs/localidades
 */
public class IbgeLocalidadesClient {

    public static final String FONTE = "IBGE - API de Localidades";

    private final ClienteHttpExterno http;
    private final String urlBase;
    private final CacheTemporario<String, Map<String, String>> cache = new CacheTemporario<>(Duration.ofHours(24), 30);

    public IbgeLocalidadesClient(ClienteHttpExterno http, String urlBase) {
        this.http = http;
        this.urlBase = urlBase.endsWith("/") ? urlBase.substring(0, urlBase.length() - 1) : urlBase;
    }

    public Optional<String> codigoMunicipio(String uf, String nomeMunicipio) {
        if (Texto.vazio(uf) || Texto.vazio(nomeMunicipio)) {
            return Optional.empty();
        }
        Map<String, String> municipios = cache.obterOuCalcular(uf.toUpperCase(), () -> carregar(uf.toUpperCase()));
        return Optional.ofNullable(municipios.get(Texto.normalizar(nomeMunicipio)));
    }

    private Map<String, String> carregar(String uf) {
        JsonNode json = http.getJson(FONTE, URI.create(urlBase + "/estados/" + uf + "/municipios"));
        Map<String, String> mapa = new HashMap<>();
        if (json != null && json.isArray()) {
            for (JsonNode m : json.values()) {
                String id = JsonUtil.texto(m, "id");
                String nome = JsonUtil.texto(m, "nome");
                if (id != null && nome != null) {
                    mapa.put(Texto.normalizar(nome), id);
                }
            }
        }
        return mapa;
    }
}
