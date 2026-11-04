package com.alertalar.entity.enums;

/** Classificações possíveis de uma consulta de risco. */
public enum ClassificacaoRisco {
    BAIXO("Baixo risco"),
    MODERADO("Risco moderado"),
    ALTO("Alto risco"),
    DADOS_INSUFICIENTES("Dados insuficientes");

    private final String rotulo;

    ClassificacaoRisco(String rotulo) {
        this.rotulo = rotulo;
    }

    public String getRotulo() {
        return rotulo;
    }
}
