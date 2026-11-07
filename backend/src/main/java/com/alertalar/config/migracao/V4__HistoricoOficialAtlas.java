package com.alertalar.config.migracao;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.GZIPInputStream;

/**
 * Carrega no banco o histórico oficial de desastres ligados a chuva de todos os municípios
 * (inundações, enxurradas, alagamentos e chuvas intensas, 1991–2025), a partir da base
 * compacta gerada do Atlas Digital de Desastres no Brasil v1.1 (MIDR/Sedec, registros do S2iD).
 *
 * <p>Executada uma única vez pelo Flyway (registrada como bean do Spring). Usa o mesmo
 * identificador ("PROT:" + protocolo do S2iD) da importação manual, então uma planilha mais
 * nova importada depois pela Moderação não duplica registros.</p>
 */
@Component
public class V4__HistoricoOficialAtlas extends BaseJavaMigration {

    public static final String RECURSO = "dados/atlas-desastres-chuva.csv.gz";
    static final String VERSAO_BASE = "Atlas Digital de Desastres no Brasil v1.1 (MIDR/Sedec, 06/08/2026)";
    static final LocalDate DATA_BASE = LocalDate.of(2026, 8, 6);
    private static final int LINHAS_POR_COMANDO = 500;
    private static final String COLUNAS = "(localizacao, municipio, estado, codigo_ibge, data_ocorrencia, descricao, "
            + "tipo_evento, origem, status_validacao, nivel_localizacao, fonte_id, identificador_externo, "
            + "data_validacao, data_registro)";
    private static final String VALORES = "(?, ?, ?, ?, ?, ?, ?, 'OFICIAL', 'CONFIRMADA', 'MUNICIPIO', ?, ?, now(), now())";

    @Override
    public void migrate(Context context) throws Exception {
        Connection conexao = context.getConnection();
        long fonteId = fonteId(conexao);
        int inseridos = 0;
        try (InputStream recurso = abrirRecurso();
             BufferedReader leitor = new BufferedReader(new InputStreamReader(new GZIPInputStream(recurso), StandardCharsets.UTF_8))) {
            String cabecalho = leitor.readLine();
            if (cabecalho == null || !cabecalho.startsWith("protocolo;uf;municipio;ibge;data")) {
                throw new IllegalStateException("Base do Atlas em formato inesperado: " + cabecalho);
            }
            List<Registro> lote = new ArrayList<>(LINHAS_POR_COMANDO);
            String linha;
            while ((linha = leitor.readLine()) != null) {
                if (linha.isBlank()) {
                    continue;
                }
                lote.add(Registro.de(linha));
                if (lote.size() == LINHAS_POR_COMANDO) {
                    inseridos += inserir(conexao, lote, fonteId);
                    lote.clear();
                }
            }
            if (!lote.isEmpty()) {
                inseridos += inserir(conexao, lote, fonteId);
            }
        }
        try (PreparedStatement ps = conexao.prepareStatement("""
                UPDATE fonte_dados
                   SET nome = ?, url = ?, status_integracao = 'ATIVA', data_atualizacao = ?, descricao = ?
                 WHERE id = ?""")) {
            ps.setString(1, "MIDR/Sedec - Atlas Digital de Desastres no Brasil (registros do S2iD)");
            ps.setString(2, "https://atlasdigital.mdr.gov.br");
            ps.setObject(3, DATA_BASE.atStartOfDay().atOffset(java.time.ZoneOffset.ofHours(-3)));
            ps.setString(4, "Registros oficiais de desastres ligados a chuva (inundações, enxurradas, alagamentos e "
                    + "chuvas intensas) de todos os municípios, 1991 a 2025, incluídos no sistema a partir da base "
                    + "consolidada v1.1 de 06/08/2026. São registros municipais: indicam que a cidade foi afetada, não "
                    + "qual rua. Versões mais novas podem ser importadas pela tela de Moderação.");
            ps.setLong(5, fonteId);
            ps.executeUpdate();
        }
        System.out.println("[AlertaLar] Histórico oficial carregado: " + inseridos + " registros (" + VERSAO_BASE + ")");
    }

    InputStream abrirRecurso() throws IOException {
        InputStream in = V4__HistoricoOficialAtlas.class.getClassLoader().getResourceAsStream(RECURSO);
        if (in == null) {
            throw new IOException("Recurso não encontrado no classpath: " + RECURSO);
        }
        return in;
    }

    private static long fonteId(Connection conexao) throws SQLException {
        try (Statement st = conexao.createStatement();
             ResultSet rs = st.executeQuery("SELECT id FROM fonte_dados WHERE codigo = 'S2ID_RECONHECIMENTOS'")) {
            if (!rs.next()) {
                throw new IllegalStateException("Fonte S2ID_RECONHECIMENTOS não cadastrada");
            }
            return rs.getLong(1);
        }
    }

    private static int inserir(Connection conexao, List<Registro> lote, long fonteId) throws SQLException {
        StringBuilder sql = new StringBuilder("INSERT INTO ocorrencia ").append(COLUNAS).append(" VALUES ");
        for (int i = 0; i < lote.size(); i++) {
            sql.append(i == 0 ? "" : ", ").append(VALORES);
        }
        sql.append(" ON CONFLICT (fonte_id, identificador_externo) WHERE identificador_externo IS NOT NULL DO NOTHING");
        try (PreparedStatement ps = conexao.prepareStatement(sql.toString())) {
            int p = 1;
            for (Registro r : lote) {
                ps.setString(p++, "Município de " + r.municipio() + "/" + r.uf() + " (registro municipal, sem endereço específico)");
                ps.setString(p++, r.municipio());
                ps.setString(p++, r.uf());
                ps.setString(p++, r.ibge());
                ps.setDate(p++, Date.valueOf(r.data()));
                ps.setString(p++, r.descricao());
                ps.setString(p++, r.tipoEvento());
                ps.setLong(p++, fonteId);
                ps.setString(p++, "PROT:" + r.protocolo());
            }
            return ps.executeUpdate();
        }
    }

    /** Uma linha da base compacta (colunas geradas por PrepararBaseAtlas). */
    record Registro(String protocolo, String uf, String municipio, String ibge, LocalDate data, String cobrade,
                    String tipologia, String status, int mortos, int desabrigados, int desalojados,
                    int casasDanificadas, int casasDestruidas) {

        static Registro de(String linha) {
            String[] c = linha.split(";", -1);
            if (c.length < 13) {
                throw new IllegalArgumentException("Linha inválida na base do Atlas: " + linha);
            }
            return new Registro(c[0], c[1], c[2], c[3], LocalDate.parse(c[4]), c[5], c[6], c[7],
                    Integer.parseInt(c[8]), Integer.parseInt(c[9]), Integer.parseInt(c[10]),
                    Integer.parseInt(c[11]), Integer.parseInt(c[12]));
        }

        String tipoEvento() {
            return switch (tipologia) {
                case "Inundações" -> "INUNDACAO";
                case "Enxurradas" -> "ENXURRADA";
                case "Alagamentos" -> "ALAGAMENTO";
                case "Chuvas Intensas" -> "CHUVA_INTENSA";
                default -> "OUTRO";
            };
        }

        String descricao() {
            String evento = switch (tipologia) {
                case "Inundações" -> "Inundação registrada";
                case "Enxurradas" -> "Enxurrada registrada";
                case "Alagamentos" -> "Alagamento registrado";
                case "Chuvas Intensas" -> "Chuvas intensas registradas";
                default -> tipologia + " (registro)";
            };
            StringBuilder sb = new StringBuilder(evento).append(" pelo município");
            if (cobrade.matches("\\d{5}")) {
                sb.append(" (COBRADE ").append(String.join(".", cobrade.split(""))).append(')');
            }
            sb.append(". ").append("Reconhecido".equalsIgnoreCase(status)
                    ? "Situação de emergência ou calamidade reconhecida pela União."
                    : "Registrado no S2iD, sem reconhecimento federal.");
            List<String> danos = new ArrayList<>();
            adicionar(danos, mortos, "morte", "mortes");
            adicionar(danos, desabrigados, "pessoa desabrigada", "pessoas desabrigadas");
            adicionar(danos, desalojados, "pessoa desalojada", "pessoas desalojadas");
            adicionar(danos, casasDanificadas, "casa danificada", "casas danificadas");
            adicionar(danos, casasDestruidas, "casa destruída", "casas destruídas");
            sb.append(danos.isEmpty() ? " Sem danos humanos ou habitacionais informados."
                    : " Danos informados: " + String.join(", ", danos) + ".");
            sb.append(" Protocolo S2iD ").append(protocolo).append(". Fonte: ").append(VERSAO_BASE).append('.');
            return sb.length() > 2000 ? sb.substring(0, 1999) : sb.toString();
        }

        private static void adicionar(List<String> lista, int n, String singular, String plural) {
            if (n > 0) {
                lista.add(NumberFormat.getIntegerInstance(Locale.of("pt", "BR")).format(n) + " " + (n == 1 ? singular : plural));
            }
        }
    }
}
