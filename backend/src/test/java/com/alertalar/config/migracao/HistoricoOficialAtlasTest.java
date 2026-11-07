package com.alertalar.config.migracao;

import com.alertalar.config.migracao.V4__HistoricoOficialAtlas.Registro;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Base oficial embutida (Atlas Digital de Desastres no Brasil v1.1) e sua conversão:
 * garante que o histórico municipal vem carregado e inclui as chuvas intensas, tipo em que
 * estão registradas, por exemplo, as enchentes de maio de 2024 no Rio Grande do Sul.
 */
class HistoricoOficialAtlasTest {

    private static List<Registro> base() throws Exception {
        InputStream in = new V4__HistoricoOficialAtlas().abrirRecurso();
        List<Registro> lista = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new GZIPInputStream(in), StandardCharsets.UTF_8))) {
            String cabecalho = r.readLine();
            assertTrue(cabecalho.startsWith("protocolo;uf;municipio;ibge;data;cobrade;tipologia;status"), cabecalho);
            String linha;
            while ((linha = r.readLine()) != null) {
                lista.add(Registro.de(linha));
            }
        }
        return lista;
    }

    @Test
    @DisplayName("Base embutida: 27.581 registros válidos de 1991 a 2025, protocolos únicos, só tipos ligados a chuva")
    void baseEmbutida() throws Exception {
        List<Registro> base = base();
        assertEquals(27_581, base.size());
        Set<String> protocolos = new HashSet<>();
        Set<String> tipos = Set.of("INUNDACAO", "ENXURRADA", "ALAGAMENTO", "CHUVA_INTENSA");
        for (Registro r : base) {
            assertTrue(protocolos.add(r.protocolo()), "protocolo repetido: " + r.protocolo());
            assertTrue(tipos.contains(r.tipoEvento()), r.tipologia());
            assertTrue(r.ibge().matches("\\d{7}"), r.ibge());
            assertEquals(2, r.uf().length());
            assertFalse(r.data().isBefore(LocalDate.of(1991, 1, 1)));
            assertFalse(r.data().isAfter(LocalDate.of(2025, 12, 31)));
            assertTrue(r.descricao().length() <= 2000);
        }
    }

    @Test
    @DisplayName("Enchentes de maio de 2024 em Canoas e Porto Alegre estão na base (como chuvas intensas)")
    void enchentesRs2024() throws Exception {
        List<Registro> base = base();
        Registro canoas = base.stream().filter(r -> r.ibge().equals("4304606") && r.data().equals(LocalDate.of(2024, 5, 2)))
                .findFirst().orElse(null);
        assertNotNull(canoas);
        assertEquals("CHUVA_INTENSA", canoas.tipoEvento());
        assertTrue(canoas.descricao().contains("reconhecida pela União"), canoas.descricao());
        assertTrue(base.stream().anyMatch(r -> r.ibge().equals("4314902") && r.data().equals(LocalDate.of(2024, 4, 29))));
    }

    @Test
    @DisplayName("Registro da base vira descrição legível com COBRADE, reconhecimento, danos e fonte")
    void descricao() {
        Registro r = Registro.de("RS-A-4304606-12100-20240502;RS;Canoas;4304606;2024-05-02;12100;Inundações;Reconhecido;"
                + "31;12000;1;1500;0");
        assertEquals("INUNDACAO", r.tipoEvento());
        String d = r.descricao();
        assertTrue(d.startsWith("Inundação registrada pelo município (COBRADE 1.2.1.0.0)."), d);
        assertTrue(d.contains("31 mortes, 12.000 pessoas desabrigadas, 1 pessoa desalojada, 1.500 casas danificadas"), d);
        assertFalse(d.contains("destruída"));
        assertTrue(d.contains("Protocolo S2iD RS-A-4304606-12100-20240502"));
        assertTrue(d.contains("Atlas Digital de Desastres no Brasil v1.1"));

        Registro semDanos = Registro.de("SC-1;SC;Blumenau;4202404;2011-09-08;13214;Chuvas Intensas;Registro;0;0;0;0;0");
        assertEquals("CHUVA_INTENSA", semDanos.tipoEvento());
        assertTrue(semDanos.descricao().contains("Registrado no S2iD, sem reconhecimento federal."));
        assertTrue(semDanos.descricao().contains("Sem danos humanos ou habitacionais informados."));
    }
}
