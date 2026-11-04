package com.alertalar.exception;

/** Violação de regra de negócio (ex.: e-mail já cadastrado, transição de status inválida). */
public class RegraNegocioException extends RuntimeException {
    public RegraNegocioException(String mensagem) {
        super(mensagem);
    }
}
