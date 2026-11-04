package com.alertalar.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UtilitariosTest {

    @Test
    @DisplayName("Normaliza textos removendo acentos, caixa e espaços extras")
    void normalizaTexto() {
        assertEquals("sao jose do norte", Texto.normalizar("  São José   do NORTE "));
        assertEquals("", Texto.normalizar(null));
    }

    @Test
    @DisplayName("Limpa caracteres de controle de textos livres")
    void limpaTexto() {
        assertEquals("Rua A", Texto.limpar("  Rua\u0000 A  "));
        assertNull(Texto.limpar("   "));
    }

    @Test
    @DisplayName("Formata CEP somente quando há 8 dígitos")
    void formataCep() {
        assertEquals("92010-000", Texto.formatarCep("92010000"));
        assertEquals("92010-000", Texto.formatarCep("92010-000"));
        assertNull(Texto.formatarCep("9201"));
    }

    @Test
    @DisplayName("Reconhece UF por sigla ou nome")
    void reconheceUf() {
        assertEquals(UnidadeFederativa.RS, UnidadeFederativa.deTexto("rs").orElseThrow());
        assertEquals(UnidadeFederativa.SP, UnidadeFederativa.deTexto("Sao Paulo").orElseThrow());
        assertTrue(UnidadeFederativa.deTexto("XX").isEmpty());
    }

    @Test
    @DisplayName("Distância de Haversine entre pontos conhecidos")
    void distancia() {
        // 0,01° de latitude ≈ 1.112 m
        double d = Geo.distanciaMetros(-29.90, -51.18, -29.91, -51.18);
        assertEquals(1112, d, 5);
    }

    @Test
    @DisplayName("Ponto mais próximo de uma linha está sobre o segmento")
    void pontoMaisProximo() {
        List<double[]> linha = List.of(new double[]{-29.91, -51.17}, new double[]{-29.93, -51.17});
        double[] p = Geo.pontoMaisProximo(-29.92, -51.18, linha);
        assertEquals(-29.92, p[0], 1e-4);
        assertEquals(-51.17, p[1], 1e-6);
        assertEquals(Geo.distanciaMetros(-29.92, -51.18, -29.92, -51.17), p[2], 2);
    }

    @Test
    @DisplayName("Ponto dentro e fora de polígono")
    void pontoNoPoligono() {
        List<double[]> quadrado = List.of(new double[]{0, 0}, new double[]{0, 1}, new double[]{1, 1}, new double[]{1, 0});
        assertTrue(Geo.dentroDoPoligono(0.5, 0.5, quadrado));
        assertFalse(Geo.dentroDoPoligono(1.5, 0.5, quadrado));
    }

    @Test
    @DisplayName("Limites do território brasileiro")
    void dentroDoBrasil() {
        assertTrue(Geo.dentroDoBrasil(-29.9, -51.1));
        assertFalse(Geo.dentroDoBrasil(40.7, -74.0));
    }

    @Test
    @DisplayName("Deslocamento por distância e rumo")
    void deslocar() {
        double[] p = Geo.deslocar(-29.9, -51.1, 300, 0);
        assertEquals(300, Geo.distanciaMetros(-29.9, -51.1, p[0], p[1]), 1);
        assertTrue(p[0] > -29.9);
    }
}
