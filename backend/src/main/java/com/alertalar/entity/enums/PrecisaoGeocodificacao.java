package com.alertalar.entity.enums;

/** Precisão geográfica com que o endereço foi localizado. */
public enum PrecisaoGeocodificacao {
    /** O número do imóvel foi encontrado. */
    NUMERO("Número do imóvel localizado"),
    /** Apenas a rua foi encontrada: o ponto representa a via, não necessariamente o imóvel. */
    RUA("Localização aproximada pela rua"),
    /** Posição confirmada ou ajustada pelo usuário no mapa. */
    MANUAL("Posição confirmada no mapa pelo usuário");

    private final String descricao;

    PrecisaoGeocodificacao(String descricao) {
        this.descricao = descricao;
    }

    public String getDescricao() {
        return descricao;
    }
}
