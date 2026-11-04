package com.alertalar.exception;

/**
 * O endereço foi encontrado apenas no nível de bairro, CEP ou cidade, o que não é preciso
 * o bastante para analisar o risco de um imóvel.
 */
public class PrecisaoInsuficienteException extends RuntimeException {

    private final Double latitudeAproximada;
    private final Double longitudeAproximada;

    public PrecisaoInsuficienteException(String mensagem, Double latitudeAproximada, Double longitudeAproximada) {
        super(mensagem);
        this.latitudeAproximada = latitudeAproximada;
        this.longitudeAproximada = longitudeAproximada;
    }

    public Double getLatitudeAproximada() {
        return latitudeAproximada;
    }

    public Double getLongitudeAproximada() {
        return longitudeAproximada;
    }
}
