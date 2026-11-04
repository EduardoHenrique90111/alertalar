package com.alertalar.integration.viacep;

import com.alertalar.integration.http.CacheTemporario;
import com.alertalar.integration.http.ClienteHttpExterno;
import com.alertalar.integration.http.JsonUtil;
import com.alertalar.util.Texto;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.time.Duration;
import java.util.Optional;

/**
 * Integração com o ViaCEP (https://viacep.com.br). Retorna logradouro, bairro, cidade,
 * UF e código IBGE. O ViaCEP não fornece coordenadas geográficas.
 */
public class ViaCepClient {

    public static final String FONTE = "ViaCEP";

    private final ClienteHttpExterno http;
    private final String urlBase;
    private final CacheTemporario<String, Optional<DadosCep>> cache = new CacheTemporario<>(Duration.ofHours(24), 5_000);

    public ViaCepClient(ClienteHttpExterno http, String urlBase) {
        this.http = http;
        this.urlBase = urlBase.endsWith("/") ? urlBase.substring(0, urlBase.length() - 1) : urlBase;
    }

    /**
     * @param cep CEP com 8 dígitos (com ou sem hífen)
     * @return dados do CEP, ou vazio se o CEP não existir
     */
    public Optional<DadosCep> buscar(String cep) {
        String digitos = Texto.somenteDigitos(cep);
        if (digitos.length() != 8) {
            return Optional.empty();
        }
        return cache.obterOuCalcular(digitos, () -> consultar(digitos));
    }

    private Optional<DadosCep> consultar(String digitos) {
        JsonNode json = http.getJson(FONTE, URI.create(urlBase + "/" + digitos + "/json/"));
        // O ViaCEP responde {"erro": true} (ou "true") para CEPs inexistentes
        String erro = JsonUtil.texto(json, "erro");
        if (erro != null && erro.equalsIgnoreCase("true")) {
            return Optional.empty();
        }
        String cidade = JsonUtil.texto(json, "localidade");
        String uf = JsonUtil.texto(json, "uf");
        if (cidade == null || uf == null) {
            return Optional.empty();
        }
        return Optional.of(new DadosCep(
                Texto.formatarCep(digitos),
                JsonUtil.texto(json, "logradouro"),
                JsonUtil.texto(json, "bairro"),
                cidade,
                uf.toUpperCase(),
                JsonUtil.texto(json, "ibge")));
    }

    public record DadosCep(String cep, String logradouro, String bairro, String cidade, String uf, String codigoIbge) {
    }
}
