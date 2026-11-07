package com.alertalar.integration.s2id;

import com.alertalar.entity.enums.TipoEvento;
import com.alertalar.exception.ArquivoInvalidoException;
import com.alertalar.util.Texto;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Lê planilhas oficiais de registros de desastres exportadas do S2iD (Sistema Integrado
 * de Informações sobre Desastres) ou do Atlas Digital de Desastres no Brasil, salvas
 * em CSV. Não há API pública documentada para esses dados, por isso a importação é manual.
 *
 * <p>Os nomes de colunas variam entre exportações; o leitor reconhece variações comuns
 * (ex.: "Cod_IBGE_Mun", "Código IBGE", "Município", "UF", "Data do Evento", "COBRADE").
 * Aproveita os desastres ligados a chuva: inundação, enxurrada, alagamento e chuvas intensas.</p>
 */
public class LeitorRegistrosOficiaisCsv {

    private static final Map<String, List<String>> ALIASES = Map.of(
            "ibge", List.of("cod_ibge_mun", "codigo_ibge", "cod_ibge", "ibge", "cod_municipio", "codigo_municipio",
                    "geocodigo", "cod_ibge_municipio", "codigo_ibge_municipio", "cd_geocmu"),
            "municipio", List.of("municipio", "nome_municipio", "nome_do_municipio", "cidade", "municipio_uf"),
            "uf", List.of("uf", "sigla_uf", "estado", "sigla_estado"),
            "data", List.of("data_evento", "data_do_evento", "data_ocorrencia", "data_da_ocorrencia", "data_desastre",
                    "data_do_desastre", "dt_evento", "data"),
            "ano", List.of("ano", "ano_evento", "ano_ocorrencia", "ano_do_evento"),
            "cobrade", List.of("cobrade", "cod_cobrade", "codigo_cobrade", "cobrade_codigo"),
            "tipo", List.of("desastre", "tipologia", "tipo_desastre", "tipo_de_desastre", "descricao_tipologia",
                    "evento", "descricao_cobrade", "subgrupo", "grupo_de_desastre"),
            "protocolo", List.of("protocolo", "protocolo_s2id", "numero_protocolo", "n_protocolo", "registro", "id"));

    private static final List<DateTimeFormatter> FORMATOS_DATA = List.of(
            DateTimeFormatter.ofPattern("dd/MM/yyyy"),
            DateTimeFormatter.ofPattern("d/M/yyyy"),
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("dd-MM-yyyy"));

    public ResultadoLeitura ler(byte[] conteudo) {
        if (conteudo == null || conteudo.length == 0) {
            throw new ArquivoInvalidoException("O arquivo enviado está vazio.");
        }
        String texto = decodificar(conteudo);
        List<String> linhas = registros(texto);
        if (linhas.size() < 2) {
            throw new ArquivoInvalidoException("O arquivo não contém linhas de dados.");
        }
        char separador = detectarSeparador(linhas.get(0));
        List<String> cabecalho = dividir(linhas.get(0), separador);
        Map<String, Integer> colunas = mapearColunas(cabecalho);
        if (!colunas.containsKey("uf") || !(colunas.containsKey("municipio") || colunas.containsKey("ibge"))) {
            throw new ArquivoInvalidoException("Colunas obrigatórias não encontradas: é preciso ter UF e Município "
                    + "(ou código IBGE). Colunas lidas: " + String.join(", ", cabecalho));
        }
        if (!colunas.containsKey("data") && !colunas.containsKey("ano")) {
            throw new ArquivoInvalidoException("Coluna de data do evento não encontrada (ex.: \"Data do Evento\").");
        }
        if (!colunas.containsKey("cobrade") && !colunas.containsKey("tipo")) {
            throw new ArquivoInvalidoException("Coluna do tipo de desastre não encontrada (ex.: \"COBRADE\" ou \"Desastre\").");
        }

        List<RegistroOficial> registros = new ArrayList<>();
        int ignoradasNaoHidrologicas = 0;
        int ignoradasInvalidas = 0;
        for (int i = 1; i < linhas.size(); i++) {
            List<String> campos = dividir(linhas.get(i), separador);
            Optional<TipoEvento> tipo = classificar(valor(campos, colunas, "cobrade"), valor(campos, colunas, "tipo"));
            if (tipo.isEmpty()) {
                ignoradasNaoHidrologicas++;
                continue;
            }
            String uf = Optional.ofNullable(valor(campos, colunas, "uf")).map(String::toUpperCase).orElse(null);
            String municipio = valor(campos, colunas, "municipio");
            String ibge = Texto.somenteDigitos(valor(campos, colunas, "ibge"));
            DataLida data = lerData(valor(campos, colunas, "data"), valor(campos, colunas, "ano"));
            if (uf == null || uf.length() != 2 || data == null || (municipio == null && ibge.length() != 7)) {
                ignoradasInvalidas++;
                continue;
            }
            if (municipio != null && municipio.contains("/") && municipio.matches(".*/[A-Za-z]{2}$")) {
                municipio = municipio.substring(0, municipio.lastIndexOf('/')).trim();
            }
            registros.add(new RegistroOficial(
                    valor(campos, colunas, "protocolo"),
                    uf, municipio,
                    ibge.length() == 7 ? ibge : null,
                    data.data(), data.somenteAno(),
                    tipo.get(),
                    valor(campos, colunas, "cobrade"),
                    valor(campos, colunas, "tipo")));
        }
        return new ResultadoLeitura(registros, linhas.size() - 1, ignoradasNaoHidrologicas, ignoradasInvalidas);
    }

    /** COBRADE 1.2.1 = inundação, 1.2.2 = enxurrada, 1.2.3 = alagamento, 1.3.2.1.4 = chuvas intensas. */
    static Optional<TipoEvento> classificar(String cobrade, String descricao) {
        String codigo = Texto.somenteDigitos(cobrade);
        if (codigo.startsWith("121")) {
            return Optional.of(TipoEvento.INUNDACAO);
        }
        if (codigo.startsWith("122")) {
            return Optional.of(TipoEvento.ENXURRADA);
        }
        if (codigo.startsWith("123")) {
            return Optional.of(TipoEvento.ALAGAMENTO);
        }
        if (codigo.startsWith("13214")) {
            return Optional.of(TipoEvento.CHUVA_INTENSA);
        }
        String d = Texto.normalizar(descricao);
        if (d.contains("inunda")) {
            return Optional.of(TipoEvento.INUNDACAO);
        }
        if (d.contains("enxurr")) {
            return Optional.of(TipoEvento.ENXURRADA);
        }
        if (d.contains("alaga")) {
            return Optional.of(TipoEvento.ALAGAMENTO);
        }
        if (d.contains("chuvas intensas") || d.contains("chuva intensa")) {
            return Optional.of(TipoEvento.CHUVA_INTENSA);
        }
        return Optional.empty();
    }

    private record DataLida(LocalDate data, boolean somenteAno) {
    }

    private static DataLida lerData(String texto, String ano) {
        if (texto != null) {
            String t = texto.trim();
            if (t.length() > 10 && (t.charAt(10) == ' ' || t.charAt(10) == 'T')) {
                t = t.substring(0, 10); // descarta horário
            }
            for (DateTimeFormatter f : FORMATOS_DATA) {
                try {
                    return new DataLida(LocalDate.parse(t, f), false);
                } catch (DateTimeParseException ignored) {
                    // tenta o próximo formato
                }
            }
        }
        String a = Texto.somenteDigitos(ano);
        if (a.length() == 4) {
            int valor = Integer.parseInt(a);
            if (valor >= 1900 && valor <= LocalDate.now().getYear()) {
                return new DataLida(LocalDate.of(valor, 1, 1), true);
            }
        }
        return null;
    }

    private static String valor(List<String> campos, Map<String, Integer> colunas, String chave) {
        Integer idx = colunas.get(chave);
        if (idx == null || idx >= campos.size()) {
            return null;
        }
        return Texto.limpar(campos.get(idx));
    }

    /** Separa o texto em registros, juntando linhas quando um campo entre aspas contém quebra de linha. */
    static List<String> registros(String texto) {
        List<String> resultado = new ArrayList<>();
        StringBuilder atual = new StringBuilder();
        int aspas = 0;
        for (String linha : texto.split("\\r?\\n", -1)) {
            if (!atual.isEmpty()) {
                atual.append('\n');
            }
            atual.append(linha);
            for (int i = 0; i < linha.length(); i++) {
                if (linha.charAt(i) == '"') {
                    aspas++;
                }
            }
            if (aspas % 2 == 0) {
                if (!atual.toString().isBlank()) {
                    resultado.add(atual.toString());
                }
                atual.setLength(0);
                aspas = 0;
            }
        }
        if (!atual.toString().isBlank()) {
            resultado.add(atual.toString());
        }
        return resultado;
    }

    static Map<String, Integer> mapearColunas(List<String> cabecalho) {
        Map<String, Integer> normalizado = new HashMap<>();
        for (int i = 0; i < cabecalho.size(); i++) {
            String n = Texto.normalizar(cabecalho.get(i)).replaceAll("[^a-z0-9]+", "_").replaceAll("^_|_$", "");
            normalizado.putIfAbsent(n, i);
        }
        Map<String, Integer> colunas = new HashMap<>();
        ALIASES.forEach((chave, nomes) -> {
            for (String nome : nomes) {
                if (normalizado.containsKey(nome)) {
                    colunas.put(chave, normalizado.get(nome));
                    break;
                }
            }
        });
        return colunas;
    }

    static char detectarSeparador(String cabecalho) {
        long pv = cabecalho.chars().filter(c -> c == ';').count();
        long vg = cabecalho.chars().filter(c -> c == ',').count();
        long tab = cabecalho.chars().filter(c -> c == '\t').count();
        if (tab > pv && tab > vg) {
            return '\t';
        }
        return pv >= vg ? ';' : ',';
    }

    /** Divide uma linha CSV respeitando aspas ("campo; com separador"). */
    public static List<String> dividir(String linha, char separador) {
        List<String> campos = new ArrayList<>();
        StringBuilder atual = new StringBuilder();
        boolean entreAspas = false;
        for (int i = 0; i < linha.length(); i++) {
            char c = linha.charAt(i);
            if (c == '"') {
                if (entreAspas && i + 1 < linha.length() && linha.charAt(i + 1) == '"') {
                    atual.append('"');
                    i++;
                } else {
                    entreAspas = !entreAspas;
                }
            } else if (c == separador && !entreAspas) {
                campos.add(atual.toString());
                atual.setLength(0);
            } else {
                atual.append(c);
            }
        }
        campos.add(atual.toString());
        return campos;
    }

    /** UTF-8 (com ou sem BOM); se inválido, Windows-1252, comum em exportações do Excel. */
    static String decodificar(byte[] bytes) {
        int inicio = (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) ? 3 : 0;
        ByteBuffer buffer = ByteBuffer.wrap(bytes, inicio, bytes.length - inicio);
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(buffer).toString();
        } catch (CharacterCodingException e) {
            return new String(bytes, inicio, bytes.length - inicio, Charset.forName("windows-1252"));
        }
    }

    public static byte[] lerTudo(InputStream entrada) throws IOException {
        try (InputStream in = entrada) {
            return in.readAllBytes();
        }
    }

    public static InputStream de(String texto) {
        return new ByteArrayInputStream(texto.getBytes(StandardCharsets.UTF_8));
    }

    /** Registro oficial municipal de desastre hidrológico. */
    public record RegistroOficial(String protocolo, String uf, String municipio, String codigoIbge, LocalDate data,
                                  boolean somenteAno, TipoEvento tipo, String cobrade, String descricaoTipo) {
    }

    public record ResultadoLeitura(List<RegistroOficial> registros, int linhasLidas,
                                   int ignoradasNaoHidrologicas, int ignoradasInvalidas) {
    }
}
