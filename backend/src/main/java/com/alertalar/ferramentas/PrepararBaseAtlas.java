package com.alertalar.ferramentas;

import com.alertalar.integration.s2id.LeitorRegistrosOficiaisCsv;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.GZIPOutputStream;

/**
 * Ferramenta de linha de comando (não faz parte do servidor) que gera a base compacta de
 * desastres ligados a chuva a partir do CSV oficial do Atlas Digital de Desastres no Brasil
 * (MIDR/Sedec, registros do S2iD). O resultado é gravado em
 * {@code src/main/resources/dados/} e carregado no banco pela migration de histórico oficial.
 *
 * <p>Uso (na pasta backend, após {@code mvn package}):</p>
 * <pre>
 * java -cp target/classes com.alertalar.ferramentas.PrepararBaseAtlas \
 *      BD_Atlas_1991_2025_v1.1_2026.08.06_Consolidado.csv \
 *      src/main/resources/dados/atlas-desastres-chuva.csv.gz
 * </pre>
 * Baixe o CSV em https://atlasdigital.mdr.gov.br (página Downloads). O arquivo oficial vem em
 * Windows-1252 com separador ";".
 */
public final class PrepararBaseAtlas {

    /** Tipologias aproveitadas (descricao_tipologia do Atlas). */
    static final Set<String> TIPOLOGIAS = Set.of("Inundações", "Enxurradas", "Alagamentos", "Chuvas Intensas");

    static final String[] COLUNAS_SAIDA = {"protocolo", "uf", "municipio", "ibge", "data", "cobrade", "tipologia",
            "status", "mortos", "desabrigados", "desalojados", "casas_danificadas", "casas_destruidas"};

    private static final String[] COLUNAS_ATLAS = {"Protocolo_S2iD", "Sigla_UF", "Nome_Municipio", "Cod_IBGE_Mun",
            "Data_Evento", "Cod_Cobrade", "descricao_tipologia", "Status", "DH_MORTOS", "DH_DESABRIGADOS",
            "DH_DESALOJADOS", "DM_Uni Habita Danificadas", "DM_Uni Habita Destruidas"};

    private static final DateTimeFormatter DATA_BR = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private PrepararBaseAtlas() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            System.err.println("Uso: PrepararBaseAtlas <CSV do Atlas> <saida.csv.gz>");
            System.exit(2);
        }
        Resultado r;
        try (InputStream in = Files.newInputStream(Path.of(args[0]))) {
            r = converter(in, Charset.forName("windows-1252"));
        }
        Path saida = Path.of(args[1]);
        if (saida.getParent() != null) {
            Files.createDirectories(saida.getParent());
        }
        try (BufferedWriter w = new BufferedWriter(new OutputStreamWriter(
                new GZIPOutputStream(Files.newOutputStream(saida)), StandardCharsets.UTF_8))) {
            w.write(String.join(";", COLUNAS_SAIDA));
            w.write('\n');
            for (String[] linha : r.linhas()) {
                w.write(String.join(";", linha));
                w.write('\n');
            }
        }
        System.out.printf("Linhas lidas: %d%nRegistros gravados: %d%nIgnorados (outras tipologias): %d%n"
                        + "Descartados (dados inválidos): %d%nPor tipologia: %s%nArquivo: %s (%d bytes)%n",
                r.lidas(), r.linhas().size(), r.outrasTipologias(), r.invalidos(), r.porTipologia(), saida,
                Files.size(saida));
    }

    record Resultado(List<String[]> linhas, int lidas, int outrasTipologias, int invalidos,
                     Map<String, Integer> porTipologia) {
    }

    static Resultado converter(InputStream entrada, Charset charset) throws IOException {
        BufferedReader leitor = new BufferedReader(new InputStreamReader(entrada, charset));
        String cabecalho = leitor.readLine();
        if (cabecalho == null) {
            throw new IOException("Arquivo vazio");
        }
        if (!cabecalho.isEmpty() && cabecalho.charAt(0) == '﻿') {
            cabecalho = cabecalho.substring(1);
        }
        List<String> nomes = LeitorRegistrosOficiaisCsv.dividir(cabecalho, ';');
        Map<String, Integer> indice = new HashMap<>();
        for (int i = 0; i < nomes.size(); i++) {
            indice.putIfAbsent(nomes.get(i).trim(), i);
        }
        int[] pos = new int[COLUNAS_ATLAS.length];
        for (int i = 0; i < COLUNAS_ATLAS.length; i++) {
            Integer p = indice.get(COLUNAS_ATLAS[i]);
            if (p == null) {
                throw new IOException("Coluna obrigatória ausente no CSV do Atlas: " + COLUNAS_ATLAS[i]);
            }
            pos[i] = p;
        }
        List<String[]> saida = new ArrayList<>();
        Map<String, Integer> porTipologia = new TreeMap<>();
        int lidas = 0;
        int outras = 0;
        int invalidos = 0;
        String linha;
        while ((linha = lerRegistro(leitor)) != null) {
            if (linha.isBlank()) {
                continue;
            }
            lidas++;
            List<String> campos = LeitorRegistrosOficiaisCsv.dividir(linha, ';');
            String tipologia = campo(campos, pos[6]);
            if (!TIPOLOGIAS.contains(tipologia)) {
                outras++;
                continue;
            }
            String protocolo = campo(campos, pos[0]);
            String uf = campo(campos, pos[1]).toUpperCase();
            String municipio = campo(campos, pos[2]).replace(';', ',');
            String ibge = campo(campos, pos[3]);
            LocalDate data;
            try {
                data = LocalDate.parse(campo(campos, pos[4]), DATA_BR);
            } catch (RuntimeException e) {
                invalidos++;
                continue;
            }
            if (protocolo.isEmpty() || uf.length() != 2 || !ibge.matches("\\d{7}") || municipio.isEmpty()) {
                invalidos++;
                continue;
            }
            String[] registro = {protocolo, uf, municipio, ibge, data.toString(), campo(campos, pos[5]), tipologia,
                    campo(campos, pos[7]), inteiro(campo(campos, pos[8])), inteiro(campo(campos, pos[9])),
                    inteiro(campo(campos, pos[10])), inteiro(campo(campos, pos[11])), inteiro(campo(campos, pos[12]))};
            saida.add(registro);
            porTipologia.merge(tipologia, 1, Integer::sum);
        }
        // Ordenado por município e data: comprime melhor e facilita conferências
        saida.sort(Comparator.<String[], String>comparing(r -> r[3]).thenComparing(r -> r[4]).thenComparing(r -> r[0]));
        return new Resultado(saida, lidas, outras, invalidos, porTipologia);
    }

    /**
     * Lê um registro completo: campos entre aspas podem conter quebras de linha (ex.: descrições
     * de danos), então as linhas são juntadas enquanto houver aspas abertas.
     */
    static String lerRegistro(BufferedReader leitor) throws IOException {
        String linha = leitor.readLine();
        if (linha == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(linha);
        while (aspasAbertas(sb)) {
            String continuacao = leitor.readLine();
            if (continuacao == null) {
                break;
            }
            sb.append('\n').append(continuacao);
        }
        return sb.toString();
    }

    private static boolean aspasAbertas(CharSequence texto) {
        int aspas = 0;
        for (int i = 0; i < texto.length(); i++) {
            if (texto.charAt(i) == '"') {
                aspas++;
            }
        }
        return aspas % 2 == 1;
    }

    private static String campo(List<String> campos, int i) {
        return i < campos.size() ? campos.get(i).trim() : "";
    }

    private static String inteiro(String valor) {
        String v = valor.replace(".", "").replace(",", ".").trim();
        if (v.isEmpty()) {
            return "0";
        }
        try {
            return String.valueOf(Math.max(0, Math.round(Double.parseDouble(v))));
        } catch (NumberFormatException e) {
            return "0";
        }
    }
}
