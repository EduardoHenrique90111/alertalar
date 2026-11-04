package com.alertalar.integration.http;

import com.alertalar.exception.IntegracaoExternaException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Cliente HTTP único para todas as fontes externas. Centraliza tempo limite, User-Agent,
 * leitura de JSON e a conversão de falhas em {@link IntegracaoExternaException},
 * sem expor detalhes internos ao usuário final.
 */
public class ClienteHttpExterno {

    private static final Logger log = LoggerFactory.getLogger(ClienteHttpExterno.class);

    private final RestClient restClient;
    private final JsonMapper jsonMapper;

    public ClienteHttpExterno(RestClient restClient, JsonMapper jsonMapper) {
        this.restClient = restClient;
        this.jsonMapper = jsonMapper;
    }

    public JsonNode getJson(String fonte, URI uri) {
        try {
            String corpo = restClient.get()
                    .uri(uri)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(String.class);
            return lerJson(fonte, corpo);
        } catch (RestClientException e) {
            throw traduzir(fonte, e);
        }
    }

    public JsonNode postFormularioJson(String fonte, URI uri, Map<String, String> campos) {
        String corpoFormulario = campos.entrySet().stream()
                .map(e -> codificar(e.getKey()) + "=" + codificar(e.getValue()))
                .collect(Collectors.joining("&"));
        try {
            String corpo = restClient.post()
                    .uri(uri)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(corpoFormulario)
                    .retrieve()
                    .body(String.class);
            return lerJson(fonte, corpo);
        } catch (RestClientException e) {
            throw traduzir(fonte, e);
        }
    }

    private JsonNode lerJson(String fonte, String corpo) {
        if (corpo == null || corpo.isBlank()) {
            throw new IntegracaoExternaException(fonte, "A fonte " + fonte + " retornou uma resposta vazia.");
        }
        try {
            return jsonMapper.readTree(corpo);
        } catch (JacksonException e) {
            log.warn("Resposta não-JSON recebida de {}", fonte);
            throw new IntegracaoExternaException(fonte, "A fonte " + fonte + " retornou dados em formato inesperado.", e);
        }
    }

    private IntegracaoExternaException traduzir(String fonte, RestClientException e) {
        if (e instanceof RestClientResponseException resposta) {
            int status = resposta.getStatusCode().value();
            log.warn("Fonte {} respondeu HTTP {}", fonte, status);
            String detalhe = status == 429 ? "limite de requisições da fonte atingido" : "HTTP " + status;
            return new IntegracaoExternaException(fonte, "A fonte " + fonte + " não atendeu a solicitação (" + detalhe + ").", e);
        }
        if (e instanceof ResourceAccessException) {
            log.warn("Fonte {} inacessível: {}", fonte, e.getMessage());
            return new IntegracaoExternaException(fonte, "A fonte " + fonte + " não respondeu a tempo ou está inacessível.", e);
        }
        log.warn("Falha ao consultar {}: {}", fonte, e.getMessage());
        return new IntegracaoExternaException(fonte, "Falha ao consultar a fonte " + fonte + ".", e);
    }

    /** Codifica parâmetros de URL/formulário em UTF-8 (protege contra injeção de parâmetros). */
    public static String codificar(String valor) {
        return URLEncoder.encode(valor == null ? "" : valor, StandardCharsets.UTF_8);
    }

    /** Monta uma URI com parâmetros de consulta corretamente codificados. */
    public static URI uri(String base, Map<String, ?> parametros) {
        if (parametros == null || parametros.isEmpty()) {
            return URI.create(base);
        }
        String query = parametros.entrySet().stream()
                .filter(e -> e.getValue() != null)
                .map(e -> codificar(e.getKey()) + "=" + codificar(String.valueOf(e.getValue())))
                .collect(Collectors.joining("&"));
        return URI.create(base + (base.contains("?") ? "&" : "?") + query);
    }
}
