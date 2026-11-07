package com.alertalar.service;

import com.alertalar.dto.ConsultaResponse;
import com.alertalar.dto.ConsultaResponse.DimensaoResponse;
import com.alertalar.dto.ConsultaResponse.HistoricoMunicipalResponse;
import com.alertalar.dto.EnderecoRequest;
import com.alertalar.dto.EnderecoResponse;
import com.alertalar.dto.FatorResponse;
import com.alertalar.dto.FonteResponse;
import com.alertalar.dto.OcorrenciaResponse;
import com.alertalar.entity.Consulta;
import com.alertalar.entity.Endereco;
import com.alertalar.entity.FatorConsulta;
import com.alertalar.entity.Usuario;
import com.alertalar.entity.enums.CategoriaFator;
import com.alertalar.entity.enums.PrecisaoGeocodificacao;
import com.alertalar.entity.enums.SituacaoFator;
import com.alertalar.exception.RecursoNaoEncontradoException;
import com.alertalar.integration.overpass.OverpassClient.CursoDagua;
import com.alertalar.integration.sgb.SgbSetoresRiscoClient.SetorRisco;
import com.alertalar.repository.ConsultaRepository;
import com.alertalar.service.analise.AnaliseRiscoService;
import com.alertalar.service.analise.AvaliacaoFator;
import com.alertalar.service.analise.ContextoAnalise;
import com.alertalar.service.analise.ContextoAnalise.HistoricoMunicipal;
import com.alertalar.service.analise.ProximidadeAgua;
import com.alertalar.service.analise.ResultadoAnalise;
import com.alertalar.util.Geo;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Orquestra a consulta: endereço → coleta de dados → análise → persistência → resposta. */
@Service
public class ConsultaService {

    private static final int MAX_CURSOS_NO_MAPA = 12;

    private final EnderecoService enderecoService;
    private final ColetaDadosService coleta;
    private final AnaliseRiscoService analise;
    private final ConsultaRepository consultas;
    private final FonteDadosService fontes;
    private final JsonMapper jsonMapper;

    public ConsultaService(EnderecoService enderecoService, ColetaDadosService coleta, AnaliseRiscoService analise,
                           ConsultaRepository consultas, FonteDadosService fontes, JsonMapper jsonMapper) {
        this.enderecoService = enderecoService;
        this.coleta = coleta;
        this.analise = analise;
        this.consultas = consultas;
        this.fontes = fontes;
        this.jsonMapper = jsonMapper;
    }

    /**
     * Realiza a consulta completa. As chamadas externas acontecem FORA de transação de banco,
     * para não prender conexões enquanto as APIs respondem.
     */
    public UUID realizar(EnderecoRequest request, Usuario usuario) {
        Endereco endereco = enderecoService.resolverParaAnalise(request);
        ContextoAnalise contexto = coleta.coletar(endereco);
        ResultadoAnalise resultado = analise.analisar(contexto);

        Consulta consulta = new Consulta();
        consulta.setEndereco(endereco);
        consulta.setUsuario(usuario);
        consulta.setClassificacao(resultado.classificacao());
        consulta.setIndice(resultado.indice());
        consulta.setJustificativa(resultado.justificativa());
        consulta.setVersaoMetodologia(resultado.versaoMetodologia());
        consulta.setDadosMapa(montarDadosMapa(contexto));
        int ordem = 0;
        Map<String, com.alertalar.entity.FonteDados> fontesPorCodigo = fontes.listar().stream()
                .collect(Collectors.toMap(com.alertalar.entity.FonteDados::getCodigo, Function.identity()));
        for (AvaliacaoFator a : resultado.fatores()) {
            FatorConsulta f = new FatorConsulta();
            f.setOrdem(ordem++);
            f.setCodigo(a.codigo());
            f.setNome(a.nome());
            f.setCategoria(a.categoria());
            f.setSituacao(a.situacao());
            f.setPontuacao(a.pontuacao());
            f.setPeso(a.peso());
            f.setValorObservado(truncar(a.valorObservado(), 500));
            f.setExplicacao(truncar(a.explicacao(), 2000));
            f.setFonte(fontesPorCodigo.get(a.codigoFonte()));
            f.setDataReferencia(a.dataReferencia());
            consulta.adicionarFator(f);
        }
        return consultas.save(consulta).getId();
    }

    @Transactional(readOnly = true)
    public ConsultaResponse buscar(UUID id) {
        Consulta c = consultas.findWithEnderecoById(id)
                .orElseThrow(() -> new RecursoNaoEncontradoException("Consulta não encontrada."));
        Endereco e = c.getEndereco();

        List<FatorResponse> fatores = c.getFatores().stream().map(FatorResponse::de).toList();
        List<OcorrenciaResponse> proximas = coleta.ocorrenciasProximas(e.getLatitude(), e.getLongitude()).stream()
                .map(o -> OcorrenciaResponse.de(o.ocorrencia(), o.distanciaMetros()))
                .toList();
        HistoricoMunicipal historico = coleta.historicoMunicipal(e.getEstado(), e.getCodigoIbge());
        HistoricoMunicipalResponse historicoResp = new HistoricoMunicipalResponse(historico.baseImportadaParaUf(),
                historico.registros().size(),
                historico.registros().stream().limit(20).map(OcorrenciaResponse::de).toList());

        Set<String> codigosFontes = new LinkedHashSet<>();
        if (e.getCep() != null) codigosFontes.add("VIACEP");
        if (e.getPrecisao() != PrecisaoGeocodificacao.MANUAL) codigosFontes.add("NOMINATIM");
        if (e.getCodigoIbge() != null) codigosFontes.add("IBGE_LOCALIDADES");
        c.getFatores().stream().filter(f -> f.getFonte() != null).forEach(f -> codigosFontes.add(f.getFonte().getCodigo()));
        if (proximas.stream().anyMatch(o -> "OFICIAL".equals(o.origem()))) codigosFontes.add("S2ID_RECONHECIMENTOS");
        List<FonteResponse> fontesUsadas = codigosFontes.stream()
                .map(fontes::buscar).flatMap(Optional::stream).map(FonteResponse::de).toList();

        return new ConsultaResponse(c.getId(), c.getDataConsulta(), EnderecoResponse.de(e),
                c.getClassificacao().name(), c.getClassificacao().getRotulo(),
                AnaliseRiscoService.textoClassificacao(c.getClassificacao()),
                c.getIndice(), c.getJustificativa(), c.getVersaoMetodologia(),
                dimensoes(c.getFatores()), fatores, proximas, historicoResp, lerMapa(c.getDadosMapa()),
                fontesUsadas, avisos(e));
    }

    @Transactional(readOnly = true)
    public List<ConsultaResponse.Resumo> doUsuario(Long usuarioId) {
        return consultas.findByUsuarioIdOrderByDataConsultaDesc(usuarioId, PageRequest.of(0, 50)).stream()
                .map(c -> new ConsultaResponse.Resumo(c.getId(), c.getDataConsulta(), c.getEndereco().descricaoCompleta(),
                        c.getClassificacao().name(), c.getClassificacao().getRotulo()))
                .toList();
    }

    static List<DimensaoResponse> dimensoes(List<FatorConsulta> fatores) {
        List<DimensaoResponse> lista = new ArrayList<>();
        for (CategoriaFator cat : CategoriaFator.values()) {
            List<FatorConsulta> daCat = fatores.stream().filter(f -> f.getCategoria() == cat).toList();
            Optional<Integer> maior = daCat.stream()
                    .filter(f -> f.getSituacao() == SituacaoFator.AVALIADO && f.getPontuacao() != null)
                    .map(FatorConsulta::getPontuacao).max(Comparator.naturalOrder());
            boolean semRegistro = daCat.stream().anyMatch(f -> f.getSituacao() == SituacaoFator.SEM_REGISTRO);
            String nivel;
            String rotulo;
            if (maior.isPresent()) {
                nivel = switch (maior.get()) {
                    case 3 -> "FORTE";
                    case 2 -> "MODERADO";
                    case 1 -> "FRACO";
                    default -> "SEM_INDICIO";
                };
                rotulo = switch (maior.get()) {
                    case 3 -> "Indícios fortes";
                    case 2 -> "Indícios moderados";
                    case 1 -> "Indícios fracos";
                    default -> "Sem indícios nos dados";
                };
            } else if (semRegistro) {
                nivel = "SEM_REGISTRO";
                rotulo = "Nenhum registro encontrado (não significa que nunca ocorreu)";
            } else {
                nivel = "NAO_AVALIADO";
                rotulo = "Não avaliado (fonte indisponível)";
            }
            String[] textos = switch (cat) {
                case RISCO_MAPEADO -> new String[]{"Risco mapeado oficialmente",
                        "Áreas de risco delimitadas por órgão oficial (Serviço Geológico do Brasil)."};
                case SUSCETIBILIDADE -> new String[]{"Suscetibilidade geográfica",
                        "Características do terreno: proximidade de água e elevação relativa."};
                case HISTORICO -> new String[]{"Risco histórico",
                        "Desastres oficiais registrados no município (Atlas Digital/S2iD) e ocorrências próximas validadas."};
            };
            lista.add(new DimensaoResponse(cat.name(), textos[0], textos[1], nivel, rotulo));
        }
        return lista;
    }

    private static List<String> avisos(Endereco e) {
        List<String> avisos = new ArrayList<>();
        if (e.getPrecisao() == PrecisaoGeocodificacao.RUA) {
            avisos.add("O número do imóvel não foi localizado: o ponto analisado representa a rua e pode estar a "
                    + "algumas centenas de metros do imóvel. Para mais precisão, refaça a consulta confirmando a posição no mapa.");
        }
        avisos.add("A classificação resume indícios nos dados disponíveis. Não é laudo técnico, estudo hidrológico "
                + "nem previsão do tempo.");
        avisos.add("A situação atual (chuva e rios) é exibida separadamente e não altera esta classificação. "
                + "Siga sempre os alertas da Defesa Civil.");
        return avisos;
    }

    /** Geometrias reais exibidas no mapa, recortadas ao entorno do ponto para limitar o tamanho. */
    String montarDadosMapa(ContextoAnalise ctx) {
        ObjectNode raiz = jsonMapper.createObjectNode();
        raiz.putObject("ponto").put("lat", ctx.latitude()).put("lon", ctx.longitude());
        raiz.putObject("raios")
                .put("cursosAgua", ctx.parametros().raioCursosAguaMetros())
                .put("ocorrencias", ctx.parametros().raioOcorrenciasMetros())
                .put("setores", ctx.parametros().raioSetoresRiscoMetros());
        double limite = ctx.parametros().raioCursosAguaMetros() * 1.5;

        ArrayNode cursos = raiz.putArray("cursosDagua");
        if (ctx.cursosDagua().disponivel()) {
            record ComDistancia(CursoDagua c, double d) {
            }
            ctx.cursosDagua().valor().stream()
                    .map(c -> new ComDistancia(c, ProximidadeAgua.maisProximo(ctx.latitude(), ctx.longitude(), List.of(c))
                            .map(ProximidadeAgua::distanciaMetros).orElse(Double.MAX_VALUE)))
                    .sorted(Comparator.comparingDouble(ComDistancia::d))
                    .limit(MAX_CURSOS_NO_MAPA)
                    .forEach(cd -> {
                        ObjectNode n = cursos.addObject();
                        n.put("descricao", cd.c().descricao());
                        n.put("tipo", cd.c().tipo());
                        n.put("principal", cd.c().principal());
                        n.put("area", cd.c().area());
                        n.put("distanciaMetros", Math.round(cd.d()));
                        ArrayNode linhas = n.putArray("linhas");
                        for (List<double[]> parte : cd.c().partes()) {
                            for (List<double[]> trecho : recortar(parte, ctx.latitude(), ctx.longitude(), limite)) {
                                ArrayNode linha = linhas.addArray();
                                trecho.forEach(p -> linha.addArray().add(arred(p[0])).add(arred(p[1])));
                            }
                        }
                    });
            ProximidadeAgua.maisProximo(ctx.latitude(), ctx.longitude(), ctx.cursosDagua().valor())
                    .ifPresent(a -> raiz.putObject("aguaMaisProxima")
                            .put("lat", arred(a.latitude())).put("lon", arred(a.longitude()))
                            .put("descricao", a.curso().descricao())
                            .put("distanciaMetros", Math.round(a.distanciaMetros())));
        }

        ArrayNode setores = raiz.putArray("setoresRisco");
        if (ctx.setoresRisco().disponivel()) {
            for (SetorRisco s : ctx.setoresRisco().valor()) {
                ObjectNode n = setores.addObject();
                n.put("numero", s.numeroSetor());
                n.put("grau", s.grauRisco());
                n.put("tipologia", s.tipologia());
                n.put("local", s.local());
                n.put("hidrologico", s.hidrologico());
                n.put("anoSetorizacao", s.dataSetorizacao() == null ? null : s.dataSetorizacao().getYear());
                ArrayNode aneis = n.putArray("aneis");
                for (List<double[]> anel : s.aneis()) {
                    ArrayNode a = aneis.addArray();
                    anel.forEach(p -> a.addArray().add(arred(p[0])).add(arred(p[1])));
                }
            }
        }
        return jsonMapper.writeValueAsString(raiz);
    }

    /** Mantém apenas os trechos da linha próximos ao ponto, quebrando onde a linha sai da área. */
    static List<List<double[]>> recortar(List<double[]> linha, double lat, double lon, double limiteMetros) {
        List<List<double[]>> trechos = new ArrayList<>();
        List<double[]> atual = new ArrayList<>();
        for (double[] p : linha) {
            if (Geo.distanciaMetros(lat, lon, p[0], p[1]) <= limiteMetros) {
                atual.add(p);
            } else if (!atual.isEmpty()) {
                atual.add(p); // inclui o ponto de saída para a linha não terminar "no ar"
                if (atual.size() > 1) trechos.add(atual);
                atual = new ArrayList<>();
            }
        }
        if (atual.size() > 1) trechos.add(atual);
        return trechos;
    }

    private JsonNode lerMapa(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return jsonMapper.readTree(json);
        } catch (JacksonException e) {
            return null;
        }
    }

    private static double arred(double v) {
        return Math.round(v * 1e6) / 1e6;
    }

    private static String truncar(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
