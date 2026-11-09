package com.alertalar.service;

import com.alertalar.dto.OcorrenciaAdminResponse;
import com.alertalar.dto.OcorrenciaRequest;
import com.alertalar.dto.OcorrenciaResponse;
import com.alertalar.dto.PaginaResponse;
import com.alertalar.dto.ValidacaoRequest;
import com.alertalar.entity.Ocorrencia;
import com.alertalar.entity.Usuario;
import com.alertalar.entity.enums.NivelLocalizacao;
import com.alertalar.entity.enums.OrigemOcorrencia;
import com.alertalar.entity.enums.StatusValidacao;
import com.alertalar.exception.IntegracaoExternaException;
import com.alertalar.exception.RecursoNaoEncontradoException;
import com.alertalar.exception.RegraNegocioException;
import com.alertalar.integration.ibge.IbgeLocalidadesClient;
import com.alertalar.repository.OcorrenciaRepository;
import com.alertalar.util.Geo;
import com.alertalar.util.Texto;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Registro, listagem e moderação de ocorrências. Relatos de usuários entram como PENDENTE
 * e só passam a contar na análise de risco quando um administrador os CONFIRMA.
 */
@Service
public class OcorrenciaService {

    public static final String FONTE_RELATOS = "ALERTALAR_RELATOS";
    private static final int TAMANHO_MAXIMO_PAGINA = 100;

    private final OcorrenciaRepository repository;
    private final FonteDadosService fontes;
    private final IbgeLocalidadesClient ibge;
    private final ColetaDadosService coleta;

    public OcorrenciaService(OcorrenciaRepository repository, FonteDadosService fontes, IbgeLocalidadesClient ibge,
                             ColetaDadosService coleta) {
        this.repository = repository;
        this.fontes = fontes;
        this.ibge = ibge;
        this.coleta = coleta;
    }

    @Transactional
    public OcorrenciaResponse registrarRelato(OcorrenciaRequest req, Usuario usuario) {
        if (req.latitude() != null && !Geo.dentroDoBrasil(req.latitude(), req.longitude())) {
            throw new RegraNegocioException("As coordenadas informadas estão fora do território brasileiro.");
        }
        String uf = req.estado().trim().toUpperCase();
        String municipio = Texto.limpar(req.municipio());
        Ocorrencia o = new Ocorrencia();
        o.setLocalizacao(Texto.limpar(req.localizacao()));
        o.setMunicipio(municipio);
        o.setEstado(uf);
        o.setCodigoIbge(codigoIbge(uf, municipio));
        o.setDataOcorrencia(req.dataOcorrencia());
        o.setDescricao(Texto.limpar(req.descricao()));
        o.setTipoEvento(req.tipoEvento());
        o.setOrigem(OrigemOcorrencia.RELATO_USUARIO);
        o.setStatusValidacao(StatusValidacao.PENDENTE);
        o.setLatitude(req.latitude());
        o.setLongitude(req.longitude());
        o.setNivelLocalizacao(req.latitude() != null ? NivelLocalizacao.PONTO : NivelLocalizacao.MUNICIPIO);
        o.setFonte(fontes.obrigatoria(FONTE_RELATOS));
        o.setNomeRelator(Texto.limpar(req.nomeRelator()));
        o.setContatoRelator(Texto.limpar(req.contatoRelator()));
        o.setUsuario(usuario);
        if (o.getNomeRelator() == null && usuario != null) {
            o.setNomeRelator(usuario.getNome());
        }
        return OcorrenciaResponse.de(repository.save(o));
    }

    /** Listagem pública (somente ocorrências confirmadas). */
    @Transactional(readOnly = true)
    public PaginaResponse<OcorrenciaResponse> listarConfirmadas(String estado, String municipio, OrigemOcorrencia origem,
                                                                int pagina, int tamanho) {
        PageRequest page = PageRequest.of(Math.max(0, pagina), Math.clamp(tamanho, 1, TAMANHO_MAXIMO_PAGINA),
                Sort.by(Sort.Direction.DESC, "dataOcorrencia").and(Sort.by("id")));
        String uf = Texto.vazio(estado) ? null : estado.trim().toUpperCase();
        String mun = Texto.vazio(municipio) ? null : municipio.trim().toLowerCase();
        return PaginaResponse.de(repository.listarConfirmadas(uf, mun, origem, page), OcorrenciaResponse::de);
    }

    /** Ocorrências confirmadas com coordenadas ao redor de um ponto (para o mapa). */
    @Transactional(readOnly = true)
    public List<OcorrenciaResponse> confirmadasProximas(double lat, double lon, int raioMetros, int anos) {
        if (!Geo.dentroDoBrasil(lat, lon)) {
            throw new RegraNegocioException("As coordenadas informadas estão fora do território brasileiro.");
        }
        int raio = Math.clamp(raioMetros, 50, 10_000);
        return coleta.ocorrenciasNoRaio(lat, lon, raio, Math.clamp(anos, 1, 100)).stream()
                .map(p -> OcorrenciaResponse.de(p.ocorrencia(), p.distanciaMetros()))
                .toList();
    }

    /**
     * Relatos de usuários para a moderação, do mais recente para o mais antigo, com busca opcional
     * (local, município, descrição, nome ou contato do relator). Registros oficiais ficam de fora.
     */
    @Transactional(readOnly = true)
    public PaginaResponse<OcorrenciaAdminResponse> listarParaModeracao(StatusValidacao status, String busca, int pagina,
                                                                       int tamanho) {
        PageRequest page = PageRequest.of(Math.max(0, pagina), Math.clamp(tamanho, 1, TAMANHO_MAXIMO_PAGINA),
                Sort.by(Sort.Direction.DESC, "dataRegistro").and(Sort.by(Sort.Direction.DESC, "id")));
        return PaginaResponse.de(repository.listarRelatosParaModeracao(status, padraoBusca(busca), page),
                OcorrenciaAdminResponse::de);
    }

    /** "Dom João" → "%dom joão%", com %, _ e ! escapados (o texto digitado nunca vira curinga). */
    static String padraoBusca(String busca) {
        String limpo = Texto.limpar(busca);
        if (limpo == null) {
            return null;
        }
        String escapado = limpo.toLowerCase(java.util.Locale.ROOT)
                .replace("!", "!!").replace("%", "!%").replace("_", "!_");
        return "%" + escapado + "%";
    }

    @Transactional
    public OcorrenciaAdminResponse validar(Long id, ValidacaoRequest req, Usuario moderador) {
        if (req.status() == StatusValidacao.PENDENTE) {
            throw new RegraNegocioException("A decisão deve ser CONFIRMADA ou REJEITADA.");
        }
        Ocorrencia o = repository.findById(id)
                .orElseThrow(() -> new RecursoNaoEncontradoException("Ocorrência não encontrada."));
        if (o.getOrigem() == OrigemOcorrencia.OFICIAL) {
            throw new RegraNegocioException("Registros oficiais não passam por moderação.");
        }
        o.setStatusValidacao(req.status());
        o.setObservacaoValidacao(Texto.limpar(req.observacao()));
        o.setValidadoPor(moderador);
        o.setDataValidacao(Instant.now());
        if (req.status() == StatusValidacao.CONFIRMADA) {
            fontes.registrarAcesso(FONTE_RELATOS, Instant.now());
        }
        return OcorrenciaAdminResponse.de(o);
    }

    @Transactional(readOnly = true)
    public long contarPendentes() {
        return repository.countByStatusValidacao(StatusValidacao.PENDENTE);
    }

    private String codigoIbge(String uf, String municipio) {
        try {
            return ibge.codigoMunicipio(uf, municipio).orElse(null);
        } catch (IntegracaoExternaException e) {
            return null;
        }
    }
}
