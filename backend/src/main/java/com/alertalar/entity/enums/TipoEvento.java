package com.alertalar.entity.enums;

/** Tipos de evento hidrológico tratados pelo sistema (nomenclatura da COBRADE). */
public enum TipoEvento {
    ALAGAMENTO("Alagamento"),
    INUNDACAO("Inundação"),
    ENXURRADA("Enxurrada"),
    OUTRO("Outro evento relacionado a chuvas");

    private final String rotulo;

    TipoEvento(String rotulo) {
        this.rotulo = rotulo;
    }

    public String getRotulo() {
        return rotulo;
    }
}
