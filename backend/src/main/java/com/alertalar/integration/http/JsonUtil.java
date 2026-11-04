package com.alertalar.integration.http;

import tools.jackson.databind.JsonNode;

/** Leitura tolerante de campos JSON das APIs externas (campos ausentes viram null). */
public final class JsonUtil {

    private JsonUtil() {
    }

    public static String texto(JsonNode no, String campo) {
        if (no == null) {
            return null;
        }
        JsonNode valor = no.path(campo);
        if (valor.isMissingNode() || valor.isNull()) {
            return null;
        }
        if (valor.isString()) {
            String s = valor.stringValue();
            return s == null || s.isBlank() ? null : s.trim();
        }
        if (valor.isNumber() || valor.isBoolean()) {
            return valor.asString();
        }
        return null;
    }

    public static Double numero(JsonNode no, String campo) {
        if (no == null) {
            return null;
        }
        return numero(no.path(campo));
    }

    public static Double numero(JsonNode valor) {
        if (valor == null || valor.isMissingNode() || valor.isNull()) {
            return null;
        }
        if (valor.isNumber()) {
            return valor.asDouble();
        }
        if (valor.isString()) {
            try {
                return Double.parseDouble(valor.stringValue().trim().replace(',', '.'));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
}
