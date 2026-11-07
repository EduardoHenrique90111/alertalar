package com.alertalar.integration.valhalla;

/** Modo de deslocamento da rota segura e o perfil ("costing") equivalente no Valhalla. */
public enum ModoRota {
    A_PE("pedestrian", "A pé"),
    CARRO("auto", "De carro");

    private final String costing;
    private final String rotulo;

    ModoRota(String costing, String rotulo) {
        this.costing = costing;
        this.rotulo = rotulo;
    }

    public String getCosting() {
        return costing;
    }

    public String getRotulo() {
        return rotulo;
    }
}
