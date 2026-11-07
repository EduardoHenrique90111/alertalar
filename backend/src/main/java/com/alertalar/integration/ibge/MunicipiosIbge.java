package com.alertalar.integration.ibge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.zip.GZIPInputStream;

/**
 * Códigos IBGE de município guardados no próprio sistema (sem depender da API do IBGE, que pode
 * ficar fora do ar ou não responder a servidores fora do Brasil).
 *
 * <p>A lista vem do Atlas Digital de Desastres no Brasil v1.1 (MIDR/Sedec), que traz o código
 * IBGE oficial de cada município com algum registro: 5.256 municípios, com as diferentes grafias
 * do nome que aparecem nos registros. Município fora da lista não tem nenhum registro no Atlas;
 * para ele, o sistema ainda tenta a API do IBGE.</p>
 */
@Component
public class MunicipiosIbge {

    public static final String RECURSO = "dados/municipios-ibge.csv.gz";
    private static final Logger log = LoggerFactory.getLogger(MunicipiosIbge.class);

    /** chave "UF|nome-simplificado" → código IBGE */
    private final Map<String, String> codigos;

    public MunicipiosIbge() {
        this(carregar());
    }

    MunicipiosIbge(Map<String, String> codigos) {
        this.codigos = Map.copyOf(codigos);
    }

    /** Lista vazia: força o uso da API do IBGE (útil em testes). */
    public static MunicipiosIbge vazio() {
        return new MunicipiosIbge(Map.of());
    }

    /** Monta a lista a partir de linhas {código, UF, nome}. */
    public static MunicipiosIbge de(List<String[]> linhas) {
        Map<String, String> mapa = new HashMap<>();
        for (String[] l : linhas) {
            mapa.putIfAbsent(chave(l[1], l[2]), l[0]);
        }
        return new MunicipiosIbge(mapa);
    }

    public Optional<String> codigo(String uf, String nomeMunicipio) {
        if (uf == null || nomeMunicipio == null || uf.isBlank() || nomeMunicipio.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(codigos.get(chave(uf, nomeMunicipio)));
    }

    public int tamanho() {
        return codigos.size();
    }

    /**
     * Simplifica o nome para comparar grafias diferentes: sem acentos, minúsculo e só letras e
     * números ("Herval D`Oeste", "Herval d'Oeste" e "HERVAL DOESTE" viram "hervaldoeste").
     */
    static String chave(String uf, String nome) {
        String semAcento = Normalizer.normalize(nome, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return uf.trim().toUpperCase(Locale.ROOT) + "|" + semAcento.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static Map<String, String> carregar() {
        Map<String, String> mapa = new HashMap<>();
        InputStream in = MunicipiosIbge.class.getClassLoader().getResourceAsStream(RECURSO);
        if (in == null) {
            log.warn("Lista local de municípios ({}) não encontrada; o código IBGE dependerá da API do IBGE", RECURSO);
            return mapa;
        }
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new GZIPInputStream(in), StandardCharsets.UTF_8))) {
            String linha = r.readLine(); // cabeçalho
            while ((linha = r.readLine()) != null) {
                String[] c = linha.split(";", 3);
                if (c.length == 3 && c[0].matches("\\d{7}")) {
                    mapa.putIfAbsent(chave(c[1], c[2]), c[0]);
                }
            }
        } catch (IOException e) {
            log.warn("Falha ao ler a lista local de municípios: {}", e.getMessage());
        }
        return mapa;
    }
}
