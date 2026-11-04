package com.alertalar.service;

import com.alertalar.dto.ImportacaoResultado;
import com.alertalar.entity.FonteDados;
import com.alertalar.entity.Ocorrencia;
import com.alertalar.entity.Usuario;
import com.alertalar.entity.enums.NivelLocalizacao;
import com.alertalar.entity.enums.OrigemOcorrencia;
import com.alertalar.entity.enums.StatusValidacao;
import com.alertalar.exception.IntegracaoExternaException;
import com.alertalar.integration.ibge.IbgeLocalidadesClient;
import com.alertalar.integration.s2id.LeitorRegistrosOficiaisCsv;
import com.alertalar.integration.s2id.LeitorRegistrosOficiaisCsv.RegistroOficial;
import com.alertalar.integration.s2id.LeitorRegistrosOficiaisCsv.ResultadoLeitura;
import com.alertalar.repository.FonteDadosRepository;
import com.alertalar.repository.OcorrenciaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Importa registros oficiais municipais (S2iD / Atlas Digital de Desastres) a partir da
 * planilha CSV baixada pelo administrador. Evita duplicidade pelo protocolo do registro.
 */
@Service
public class ImportacaoService {

    public static final String FONTE_S2ID = "S2ID_RECONHECIMENTOS";
    private static final Logger log = LoggerFactory.getLogger(ImportacaoService.class);

    private final LeitorRegistrosOficiaisCsv leitor;
    private final OcorrenciaRepository ocorrencias;
    private final FonteDadosRepository fontes;
    private final IbgeLocalidadesClient ibge;

    public ImportacaoService(LeitorRegistrosOficiaisCsv leitor, OcorrenciaRepository ocorrencias,
                             FonteDadosRepository fontes, IbgeLocalidadesClient ibge) {
        this.leitor = leitor;
        this.ocorrencias = ocorrencias;
        this.fontes = fontes;
        this.ibge = ibge;
    }

    @Transactional
    public ImportacaoResultado importarRegistrosOficiais(byte[] conteudo, String nomeArquivo, Usuario administrador) {
        ResultadoLeitura leitura = leitor.ler(conteudo);
        FonteDados fonte = fontes.findByCodigo(FONTE_S2ID)
                .orElseThrow(() -> new IllegalStateException("Fonte " + FONTE_S2ID + " não cadastrada"));
        String arquivo = nomeArquivo == null ? "planilha" : nomeArquivo.replaceAll("[^\\p{L}\\p{N}._ -]", "_");

        Set<String> vistosNoArquivo = new HashSet<>();
        Set<String> estados = new TreeSet<>();
        List<Ocorrencia> novas = new ArrayList<>();
        int duplicados = 0;
        int semIbge = 0;
        for (RegistroOficial r : leitura.registros()) {
            String identificador = identificador(r);
            if (!vistosNoArquivo.add(identificador) || ocorrencias.existsByFonteAndIdentificadorExterno(fonte, identificador)) {
                duplicados++;
                continue;
            }
            String codigoIbge = r.codigoIbge() != null ? r.codigoIbge() : buscarIbge(r.uf(), r.municipio());
            if (codigoIbge == null) {
                semIbge++;
            }
            String municipio = r.municipio() != null ? r.municipio() : "Município IBGE " + codigoIbge;
            Ocorrencia o = new Ocorrencia();
            o.setFonte(fonte);
            o.setIdentificadorExterno(identificador);
            o.setOrigem(OrigemOcorrencia.OFICIAL);
            o.setStatusValidacao(StatusValidacao.CONFIRMADA);
            o.setNivelLocalizacao(NivelLocalizacao.MUNICIPIO);
            o.setLocalizacao("Município de " + municipio + "/" + r.uf() + " (registro municipal, sem endereço específico)");
            o.setMunicipio(municipio);
            o.setEstado(r.uf());
            o.setCodigoIbge(codigoIbge);
            o.setDataOcorrencia(r.data());
            o.setTipoEvento(r.tipo());
            o.setDescricao(descricao(r, arquivo));
            o.setValidadoPor(administrador);
            o.setDataValidacao(Instant.now());
            novas.add(o);
            estados.add(r.uf());
        }
        ocorrencias.saveAll(novas);
        if (!novas.isEmpty()) {
            fonte.setDataAtualizacao(Instant.now());
            fontes.save(fonte);
        }
        log.info("Importação de registros oficiais: {} novos, {} duplicados, arquivo {}", novas.size(), duplicados, arquivo);
        return new ImportacaoResultado(arquivo, leitura.linhasLidas(), novas.size(), duplicados,
                leitura.ignoradasNaoHidrologicas(), leitura.ignoradasInvalidas(), semIbge, estados);
    }

    private String buscarIbge(String uf, String municipio) {
        try {
            return ibge.codigoMunicipio(uf, municipio).orElse(null);
        } catch (IntegracaoExternaException e) {
            return null;
        }
    }

    private static String descricao(RegistroOficial r, String arquivo) {
        StringBuilder sb = new StringBuilder("Registro oficial de ").append(r.tipo().getRotulo().toLowerCase());
        if (r.cobrade() != null) {
            sb.append(" (COBRADE ").append(r.cobrade()).append(')');
        }
        if (r.descricaoTipo() != null) {
            sb.append(" - ").append(r.descricaoTipo());
        }
        if (r.protocolo() != null) {
            sb.append(". Protocolo ").append(r.protocolo());
        }
        if (r.somenteAno()) {
            sb.append(". A fonte informa apenas o ano do evento");
        }
        sb.append(". Importado da planilha \"").append(arquivo).append("\" (S2iD/Atlas Digital - MIDR).");
        return sb.length() > 2000 ? sb.substring(0, 1999) : sb.toString();
    }

    /** Protocolo do S2iD, ou um hash estável dos campos quando a planilha não traz protocolo. */
    static String identificador(RegistroOficial r) {
        if (r.protocolo() != null && !r.protocolo().isBlank()) {
            return "PROT:" + r.protocolo().trim();
        }
        String base = String.join("|", r.uf(), String.valueOf(r.codigoIbge()), String.valueOf(r.municipio()),
                r.data().toString(), r.tipo().name(), String.valueOf(r.cobrade()));
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(base.getBytes(StandardCharsets.UTF_8));
            return "HASH:" + HexFormat.of().formatHex(hash, 0, 20);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
