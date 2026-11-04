package com.alertalar.exception;

/** O endereço informado não foi localizado pelos serviços de geocodificação ou de CEP. */
public class EnderecoNaoEncontradoException extends RuntimeException {
    public EnderecoNaoEncontradoException(String mensagem) {
        super(mensagem);
    }
}
