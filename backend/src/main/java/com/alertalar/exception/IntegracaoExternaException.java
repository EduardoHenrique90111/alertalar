package com.alertalar.exception;

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
}
