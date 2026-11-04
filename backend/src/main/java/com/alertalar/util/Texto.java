package com.alertalar.util;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/** Utilitários de texto: normalização para comparação e limpeza de entradas do usuário. */
public final class Texto {

    private static final Pattern ACENTOS = Pattern.compile("\\p{M}+");
    private static final Pattern ESPACOS = Pattern.compile("\\s+");
    /** Caracteres de controle (exceto quebra de linha e tabulação) não são aceitos em textos livres. */
    private static final Pattern CONTROLE = Pattern.compile("[\\p{Cntrl}&&[^\\n\\t]]");

    private Texto() {
    }

    /** "São José  do Norte" → "sao jose do norte". Usado para comparar nomes de cidades e ruas. */
    public static String normalizar(String valor) {
        if (valor == null) {
            return "";
        }
        String semAcento = ACENTOS.matcher(Normalizer.normalize(valor, Normalizer.Form.NFD)).replaceAll("");
        return ESPACOS.matcher(semAcento.toLowerCase(Locale.ROOT).trim()).replaceAll(" ");
    }

    /** Remove espaços extras e caracteres de controle; retorna null para texto vazio. */
    public static String limpar(String valor) {
        if (valor == null) {
            return null;
        }
        String limpo = CONTROLE.matcher(valor).replaceAll("").trim();
        limpo = limpo.replaceAll("[ \\t]+", " ");
        return limpo.isEmpty() ? null : limpo;
    }

    public static String somenteDigitos(String valor) {
        return valor == null ? "" : valor.replaceAll("\\D", "");
    }

    /** "93010000" → "93010-000". */
    public static String formatarCep(String valor) {
        String d = somenteDigitos(valor);
        if (d.length() != 8) {
            return null;
        }
        return d.substring(0, 5) + "-" + d.substring(5);
    }

    public static boolean vazio(String valor) {
        return valor == null || valor.isBlank();
    }
}
