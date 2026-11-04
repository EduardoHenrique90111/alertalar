package com.alertalar.integration.s2id;

import com.alertalar.entity.enums.TipoEvento;
import com.alertalar.exception.ArquivoInvalidoException;
import com.alertalar.integration.s2id.LeitorRegistrosOficiaisCsv.ResultadoLeitura;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Leitura de planilhas exportadas do S2iD / Atlas Digital (formato CSV). */
class LeitorRegistrosOficiaisCsvTest {

    private final LeitorRegistrosOficiaisCsv leitor = new LeitorRegistrosOficiaisCsv();

    @Test
    @DisplayName("Lê CSV com ponto e vírgula, aspas e filtra somente desastres hidrológicos")
    void leEFiltra() {
        String csv = """
                Protocolo;UF;Município;Cod_IBGE_Mun;Data do Evento;COBRADE;Desastre
                RS-A-4304606-12100-20240501;RS;Canoas;4304606;01/05/2024;1.2.1.0.0;Inundações
                RS-A-4304606-13214-20240120;RS;Canoas;4304606;20/01/2024;1.3.2.1.4;Tempestade Local/Convectiva - Chuvas Intensas
                RS-A-4314902-12300-20231110;RS;"Porto Alegre";4314902;10/11/2023 14:00;1.2.3.0.0;Alagamentos
                ;RS;;;;1.2.2.0.0;Enxurradas
                """;
        ResultadoLeitura r = leitor.ler(csv.getBytes(StandardCharsets.UTF_8));
        assertEquals(4, r.linhasLidas());
        assertEquals(2, r.registros().size());
        assertEquals(1, r.ignoradasNaoHidrologicas());
        assertEquals(1, r.ignoradasInvalidas());
        var primeiro = r.registros().get(0);
        assertEquals(TipoEvento.INUNDACAO, primeiro.tipo());
        assertEquals(LocalDate.of(2024, 5, 1), primeiro.data());
        assertEquals("4304606", primeiro.codigoIbge());
        assertEquals("Porto Alegre", r.registros().get(1).municipio());
        assertEquals(TipoEvento.ALAGAMENTO, r.registros().get(1).tipo());
    }

    @Test
    @DisplayName("Aceita vírgula como separador, Windows-1252 e somente o ano do evento")
    void variacoesDeFormato() {
        String csv = "Sigla_UF,Nome_Municipio,Ano,Tipologia\nSC,São José,2022,Enxurradas\n";
        ResultadoLeitura r = leitor.ler(csv.getBytes(Charset.forName("windows-1252")));
        assertEquals(1, r.registros().size());
        var reg = r.registros().get(0);
        assertEquals("São José", reg.municipio());
        assertEquals(TipoEvento.ENXURRADA, reg.tipo());
        assertTrue(reg.somenteAno());
        assertEquals(LocalDate.of(2022, 1, 1), reg.data());
    }

    @Test
    @DisplayName("Sem colunas obrigatórias, explica o que falta")
    void colunasObrigatorias() {
        ArquivoInvalidoException ex = assertThrows(ArquivoInvalidoException.class,
                () -> leitor.ler("nome;valor\na;1\n".getBytes(StandardCharsets.UTF_8)));
        assertTrue(ex.getMessage().contains("UF"));
    }

    @Test
    @DisplayName("Arquivo vazio é recusado")
    void vazio() {
        assertThrows(ArquivoInvalidoException.class, () -> leitor.ler(new byte[0]));
    }
}
