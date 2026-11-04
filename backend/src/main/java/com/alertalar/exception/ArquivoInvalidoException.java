package com.alertalar.exception;

/** Arquivo de importação em formato não reconhecido. */
public class ArquivoInvalidoException extends RuntimeException {
    public ArquivoInvalidoException(String mensagem) {
        super(mensagem);
    }
}
