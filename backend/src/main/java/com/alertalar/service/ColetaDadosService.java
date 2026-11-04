package com.alertalar.service;

import com.alertalar.config.AlertaLarProperties;
import com.alertalar.entity.Endereco;
import com.alertalar.entity.Ocorrencia;
import com.alertalar.entity.enums.NivelLocalizacao;
import com.alertalar.entity.enums.OrigemOcorrencia;
import com.alertalar.entity.enums.StatusValidacao;
import com.alertalar.integration.http.CacheTemporario;
import com.alertalar.integration.openmeteo.ElevacaoClient;
import com.alertalar.integration.overpass.OverpassClient;
import com.alertalar.integration.overpass.OverpassClient.CursoDagua;
import com.alertalar.integration.sgb.SgbSetoresRiscoClient;
import com.alertalar.integration.sgb.SgbSetoresRiscoClient.SetorRisco;
import com.alertalar.repository.OcorrenciaRepository;
import com.alertalar.service.analise.ContextoAnalise;
import com.alertalar.service.analise.ContextoAnalise.HistoricoMunicipal;
import com.alertalar.service.analise.ContextoAnalise.OcorrenciaProxima;
import com.alertalar.service.analise.ContextoAnalise.PerfilElevacao;
import com.alertalar.service.analise.Dado;
import com.alertalar.service.analise.ProximidadeAgua;
import com.alertalar.util.Geo;
import com.alertalar.util.Texto;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Coleta, de forma independente e em paralelo, os dados reais de cada fonte para um ponto.
 * A falha de uma fonte vira "indisponível" naquele fator, sem interromper as demais.
 */
@Service
public class ColetaDadosService {

    static final int RAIO_ENTORNO_METROS = 300;

    private final OverpassClient overpass;
    private final ElevacaoClient elevacao;
    private final SgbSetoresRiscoClient sgb;
    private final OcorrenciaRepository ocorrencias;
    private final FonteDadosService fontes;
    private final AlertaLarProperties.Analise parametros;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    // Cache por coordenada (~1 m) para não repetir consultas às APIs públicas em sequência
    private final CacheTemporario<String, List<CursoDagua>> cacheCursos = new CacheTemporario<>(Duration.ofHours(6), 2_000);
    private final CacheTemporario<String, List<SetorRisco>> cacheSetores = new CacheTemporario<>(Duration.ofHours(6), 2_000);
    private final CacheTemporario<String, PerfilElevacao> cacheElevacao = new CacheTemporario<>(Duration.ofDays(7), 5_000);

    public ColetaDadosService(OverpassClient overpass, ElevacaoClient elevacao, SgbSetoresRiscoClient sgb,
                              OcorrenciaRepository ocorrencias, FonteDadosService fontes, AlertaLarProperties props) {
        this.overpass = overpass;
        this.elevacao = elevacao;
        this.sgb = sgb;
        this.ocorrencias = ocorrencias;
        this.fontes = fontes;
        this.parametros = props.analise();
    }

    public ContextoAnalise coletar(Endereco endereco) {
        double lat = endereco.getLatitude();
        double lon = endereco.getLongitude();
        String chave = String.format(Locale.ROOT, "%.5f,%.5f", lat, lon);

        CompletableFuture<Dado<List<CursoDagua>>> futuroCursos = CompletableFuture.supplyAsync(() -> Dado.coletar(() ->
                cacheCursos.obterOuCalcular(chave, () -> overpass.buscarProximos(lat, lon, parametros.raioCursosAguaMetros()))), executor);
        CompletableFuture<Dado<List<SetorRisco>>> futuroSetores = CompletableFuture.supplyAsync(() -> Dado.coletar(() ->
                cacheSetores.obterOuCalcular(chave, () -> sgb.buscarProximos(lat, lon, parametros.raioSetoresRiscoMetros()))), executor);
        // A elevação depende do curso d'água mais próximo (ponto de referência do desnível)
        // (só guarda em cache quando os cursos d'água estavam disponíveis, para não fixar um perfil incompleto)
        CompletableFuture<Dado<PerfilElevacao>> futuroElevacao = futuroCursos.thenApplyAsync(cursos -> Dado.coletar(() ->
                cursos.disponivel()
                        ? cacheElevacao.obterOuCalcular(chave, () -> perfilElevacao(lat, lon, cursos))
                        : perfilElevacao(lat, lon, cursos)), executor);

        Dado<List<OcorrenciaProxima>> proximas = Dado.coletar(() -> ocorrenciasProximas(lat, lon));
        Dado<HistoricoMunicipal> historico = Dado.coletar(() -> historicoMunicipal(endereco.getEstado(), endereco.getCodigoIbge()));

        Dado<List<CursoDagua>> cursos = futuroCursos.join();
        Dado<List<SetorRisco>> setores = futuroSetores.join();
        Dado<PerfilElevacao> perfil = futuroElevacao.join();

        if (cursos.disponivel()) fontes.registrarAcesso("OSM_CURSOS_AGUA", cursos.obtidoEm());
        if (setores.disponivel()) fontes.registrarAcesso("SGB_SETORES_RISCO", setores.obtidoEm());
        if (perfil.disponivel()) fontes.registrarAcesso("COPERNICUS_DEM", perfil.obtidoEm());

        return new ContextoAnalise(lat, lon, endereco.getEstado(), endereco.getCodigoIbge(),
                new ContextoAnalise.Parametros(parametros.raioCursosAguaMetros(), parametros.raioOcorrenciasMetros(),
                        parametros.raioSetoresRiscoMetros(), parametros.anosHistorico()),
                cursos, perfil, setores, proximas, historico);
    }

    /** Elevação no ponto, no curso d'água mais próximo e em 8 pontos ao redor (uma única requisição). */
    PerfilElevacao perfilElevacao(double lat, double lon, Dado<List<CursoDagua>> cursos) {
        List<double[]> pontos = new ArrayList<>();
        pontos.add(new double[]{lat, lon});
        for (int i = 0; i < 8; i++) {
            pontos.add(Geo.deslocar(lat, lon, RAIO_ENTORNO_METROS, i * 45.0));
        }
        Optional<ProximidadeAgua> agua = Optional.empty();
        if (cursos.disponivel()) {
            agua = ProximidadeAgua.maisProximo(lat, lon, cursos.valor(), CursoDagua::principal)
                    .or(() -> ProximidadeAgua.maisProximo(lat, lon, cursos.valor()));
        }
        agua.ifPresent(a -> pontos.add(new double[]{a.latitude(), a.longitude()}));

        List<Double> valores = elevacao.elevacoes(pontos);
        Double noPonto = valores.get(0);
        List<Double> anel = valores.subList(1, 9).stream().filter(v -> v != null).toList();
        Double media = anel.isEmpty() ? null : anel.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        Double naAgua = agua.isPresent() ? valores.get(9) : null;
        return new PerfilElevacao(noPonto, naAgua,
                agua.map(ProximidadeAgua::distanciaMetros).orElse(null),
                agua.map(a -> a.curso().descricao()).orElse(null),
                media, RAIO_ENTORNO_METROS, anel.size());
    }

    /** Ocorrências CONFIRMADAS com coordenadas dentro do raio (relatos pendentes ficam de fora). */
    public List<OcorrenciaProxima> ocorrenciasProximas(double lat, double lon) {
        return ocorrenciasNoRaio(lat, lon, parametros.raioOcorrenciasMetros(), parametros.anosHistorico());
    }

    public List<OcorrenciaProxima> ocorrenciasNoRaio(double lat, double lon, int raio, int anos) {
        double[] r = Geo.retanguloEnvolvente(lat, lon, raio);
        LocalDate desde = LocalDate.now().minusYears(anos);
        return ocorrencias.buscarNaArea(StatusValidacao.CONFIRMADA, r[0], r[1], r[2], r[3], desde).stream()
                .map(o -> new OcorrenciaProxima(o, Geo.distanciaMetros(lat, lon, o.getLatitude(), o.getLongitude())))
                .filter(o -> o.distanciaMetros() <= raio)
                .sorted(Comparator.comparingDouble(OcorrenciaProxima::distanciaMetros))
                .toList();
    }

    public HistoricoMunicipal historicoMunicipal(String uf, String codigoIbge) {
        if (Texto.vazio(codigoIbge)) {
            return new HistoricoMunicipal(List.of(), false);
        }
        boolean cobertura = ocorrencias.existsByEstadoAndOrigemAndNivelLocalizacao(uf, OrigemOcorrencia.OFICIAL, NivelLocalizacao.MUNICIPIO);
        List<Ocorrencia> registros = ocorrencias
                .findByCodigoIbgeAndNivelLocalizacaoAndStatusValidacaoAndDataOcorrenciaGreaterThanEqualOrderByDataOcorrenciaDesc(
                        codigoIbge, NivelLocalizacao.MUNICIPIO, StatusValidacao.CONFIRMADA,
                        LocalDate.now().minusYears(parametros.anosHistorico()));
        return new HistoricoMunicipal(registros, cobertura);
    }
}
