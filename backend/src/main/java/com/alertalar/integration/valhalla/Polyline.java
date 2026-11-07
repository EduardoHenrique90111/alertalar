package com.alertalar.integration.valhalla;

import java.util.ArrayList;
import java.util.List;

/**
 * Decodificação do formato "encoded polyline" usado pelo Valhalla (precisão de 6 casas decimais).
 * Referência: https://valhalla.github.io/valhalla/decoding/
 */
public final class Polyline {

    private Polyline() {
    }

    /** @return lista de [lat, lon] */
    public static List<double[]> decodificar(String codificado, int precisao) {
        List<double[]> pontos = new ArrayList<>();
        if (codificado == null || codificado.isEmpty()) {
            return pontos;
        }
        double fator = Math.pow(10, precisao);
        int indice = 0;
        long lat = 0;
        long lon = 0;
        while (indice < codificado.length()) {
            long[] r = lerValor(codificado, indice);
            lat += r[0];
            indice = (int) r[1];
            if (indice >= codificado.length()) {
                break;
            }
            r = lerValor(codificado, indice);
            lon += r[0];
            indice = (int) r[1];
            pontos.add(new double[]{lat / fator, lon / fator});
        }
        return pontos;
    }

    /** Lê um valor variável a partir de "inicio"; retorna [valor, proximoIndice]. */
    private static long[] lerValor(String s, int inicio) {
        long resultado = 0;
        int deslocamento = 0;
        int i = inicio;
        int b;
        do {
            if (i >= s.length()) {
                throw new IllegalArgumentException("Polyline truncada");
            }
            b = s.charAt(i++) - 63;
            resultado |= (long) (b & 0x1f) << deslocamento;
            deslocamento += 5;
        } while (b >= 0x20);
        long valor = (resultado & 1) != 0 ? ~(resultado >> 1) : (resultado >> 1);
        return new long[]{valor, i};
    }

    /** Codificação (usada nos testes e para simular respostas do Valhalla). */
    public static String codificar(List<double[]> pontos, int precisao) {
        double fator = Math.pow(10, precisao);
        StringBuilder sb = new StringBuilder();
        long latAnterior = 0;
        long lonAnterior = 0;
        for (double[] p : pontos) {
            long lat = Math.round(p[0] * fator);
            long lon = Math.round(p[1] * fator);
            escreverValor(sb, lat - latAnterior);
            escreverValor(sb, lon - lonAnterior);
            latAnterior = lat;
            lonAnterior = lon;
        }
        return sb.toString();
    }

    private static void escreverValor(StringBuilder sb, long valor) {
        long v = valor < 0 ? ~(valor << 1) : (valor << 1);
        while (v >= 0x20) {
            sb.append((char) ((0x20 | (v & 0x1f)) + 63));
            v >>= 5;
        }
        sb.append((char) (v + 63));
    }
}
