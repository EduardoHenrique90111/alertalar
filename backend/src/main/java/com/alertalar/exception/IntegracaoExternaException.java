package com.alertalar.exception;

import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.Optional;

/** Falha ao consultar uma fonte externa (indisponibilidade, tempo esgotado, resposta inválida). */
public class IntegracaoExternaException extends RuntimeException {

    private final String fonte;

    public IntegracaoExternaException(String fonte, String mensagem) {
        super(mensagem);
        this.fonte = fonte;
    }

    public IntegracaoExternaException(String fonte, String mensagem, Throwable causa) {
        super(mensagem, causa);
        this.fonte = fonte;
    }

    public String getFonte() {
        return fonte;
    }

    /** Código HTTP com que a fonte recusou o pedido (ex.: 429, limite atingido), se ela chegou a responder. */
    public Optional<Integer> statusHttp() {
        return getCause() instanceof RestClientResponseException r ? Optional.of(r.getStatusCode().value()) : Optional.empty();
    }

    /** Tempo de espera pedido pela fonte no cabeçalho Retry-After (em segundos), se houver. */
    public Optional<Duration> esperaPedida() {
        if (!(getCause() instanceof RestClientResponseException r) || r.getResponseHeaders() == null) {
            return Optional.empty();
        }
        String valor = r.getResponseHeaders().getFirst("Retry-After");
        if (valor == null || !valor.trim().matches("\\d{1,6}")) {
            return Optional.empty(); // o formato com data não é usado pelas fontes atuais
        }
        return Optional.of(Duration.ofSeconds(Long.parseLong(valor.trim())));
    }
}
