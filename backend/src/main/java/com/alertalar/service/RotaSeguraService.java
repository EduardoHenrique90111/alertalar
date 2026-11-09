package com.alertalar.service;

import com.alertalar.dto.RotaSeguraResponse;
import com.alertalar.dto.RotaSeguraResponse.Destino;
import com.alertalar.dto.RotaSeguraResponse.Perfil;
import com.alertalar.dto.RotaSeguraResponse.Ponto;
import com.alertalar.dto.RotaSeguraResponse.SetorMapa;
import com.alertalar.dto.RotaSeguraResponse.Trajeto;
import com.alertalar.exception.IntegracaoExternaException;
import com.alertalar.exception.RegraNegocioException;
import com.alertalar.integration.http.CacheTemporario;
import com.alertalar.integration.openmeteo.ElevacaoClient;
import com.alertalar.integration.sgb.SgbSetoresRiscoClient;
import com.alertalar.integration.sgb.SgbSetoresRiscoClient.SetorRisco;
import com.alertalar.integration.valhalla.ModoRota;
import com.alertalar.integration.valhalla.ValhallaClient;
import com.alertalar.integration.valhalla.ValhallaClient.Rota;
import com.alertalar.service.ProgressoRota.CalculoCanceladoException;
import com.alertalar.service.ProgressoRota.Estado;
import com.alertalar.service.ProgressoRota.Etapa;
import com.alertalar.service.analise.Dado;
import com.alertalar.util.Geo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Rota segura: encontra, num raio de até 3 km, um ponto de terreno mais alto que o ponto de
 * partida e fora dos setores de risco hidrológico do SGB, e calcula o caminho até ele pelas
 * ruas (a pé ou de carro), pedindo ao roteador que evite esses setores.
 *
 * <p>Critérios (documentados para o usuário):</p>
 * <ul>
 *   <li>destino pelo menos 10 m mais alto que a partida (aceita 5 m quando não há opção melhor);</li>
 *   <li>destino fora de setores de risco de inundação, enxurrada ou alagamento do SGB;</li>
 *   <li>entre os candidatos, o mais próximo em linha reta, testando até 3 se o roteador falhar;</li>
 *   <li>o perfil de elevação do caminho é verificado e trechos baixos geram aviso.</li>
 * </ul>
 * É uma sugestão baseada em dados públicos, não uma rota oficial de evacuação.
 */
@Service
public class RotaSeguraService {

    private static final Logger log = LoggerFactory.getLogger(RotaSeguraService.class);

    static final int[] DISTANCIAS_METROS = {250, 500, 800, 1200, 1700, 2300, 3000};
    static final int RUMOS = 12;
    static final double DESNIVEL_IDEAL = 10;
    static final double DESNIVEL_MINIMO = 5;
    static final int RAIO_VIZINHANCA = 1200;
    static final int RAIO_SETORES = 3500;
    /** O Valhalla limita o perímetro total dos polígonos evitados (padrão de 10 km). */
    static final double PERIMETRO_MAXIMO_EVITAR = 9000;
    static final int MAX_CHAMADAS_ROTEADOR = 4;
    static final int MAX_DESTINOS_TESTADOS = 3;
    static final int AMOSTRAS_PERFIL = 60;
    static final double TOLERANCIA_TRECHO_BAIXO = 2;
    /** Destino escolhido: distância máxima em linha reta (o desvio de áreas de risco tem limite de perímetro). */
    static final double DISTANCIA_MAXIMA_DESTINO = 20_000;

    private final ElevacaoClient elevacao;
    private final SgbSetoresRiscoClient sgb;
    private final ValhallaClient roteador;
    private final FonteDadosService fontes;
    private final CacheTemporario<String, RotaSeguraResponse> cache = new CacheTemporario<>(Duration.ofMinutes(15), 1_000);
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public RotaSeguraService(ElevacaoClient elevacao, SgbSetoresRiscoClient sgb, ValhallaClient roteador,
                             FonteDadosService fontes) {
        this.elevacao = elevacao;
        this.sgb = sgb;
        this.roteador = roteador;
        this.fontes = fontes;
    }

    public RotaSeguraResponse calcular(double lat, double lon, ModoRota modo) {
        return calcular(lat, lon, modo, ProgressoRota.NENHUM);
    }

    /** Rota até o terreno mais alto, avisando o andamento de cada consulta externa. */
    public RotaSeguraResponse calcular(double lat, double lon, ModoRota modo, ProgressoRota progresso) {
        validarPartida(lat, lon);
        String chave = String.format(Locale.ROOT, "%.4f,%.4f,%s", lat, lon, modo);
        return cache.obterOuCalcular(chave, () -> calcularSemCache(lat, lon, modo, progresso));
    }

    /** Rota segura até um destino escolhido pela pessoa (endereço, local ou ponto no mapa). */
    public RotaSeguraResponse calcularAte(double lat, double lon, double latDestino, double lonDestino, ModoRota modo) {
        return calcularAte(lat, lon, latDestino, lonDestino, modo, ProgressoRota.NENHUM);
    }

    public RotaSeguraResponse calcularAte(double lat, double lon, double latDestino, double lonDestino, ModoRota modo,
                                          ProgressoRota progresso) {
        validarDestino(lat, lon, latDestino, lonDestino);
        String chave = String.format(Locale.ROOT, "%.4f,%.4f>%.4f,%.4f,%s", lat, lon, latDestino, lonDestino, modo);
        return cache.obterOuCalcular(chave, () -> calcularAteSemCache(lat, lon, latDestino, lonDestino, modo, progresso));
    }

    /** Confere a partida antes de começar (o erro volta como resposta comum, não no meio do andamento). */
    public void validarPartida(double lat, double lon) {
        if (!Geo.dentroDoBrasil(lat, lon)) {
            throw new RegraNegocioException("O ponto de partida precisa estar no território brasileiro.");
        }
    }

    public void validarDestino(double lat, double lon, double latDestino, double lonDestino) {
        if (!Geo.dentroDoBrasil(lat, lon) || !Geo.dentroDoBrasil(latDestino, lonDestino)) {
            throw new RegraNegocioException("A partida e o destino precisam estar no território brasileiro.");
        }
        double distancia = Geo.distanciaMetros(lat, lon, latDestino, lonDestino);
        if (distancia < 30) {
            throw new RegraNegocioException("O destino está no mesmo lugar da partida. Escolha outro destino.");
        }
        if (distancia > DISTANCIA_MAXIMA_DESTINO) {
            throw new RegraNegocioException("O destino está a " + formatarDistancia(distancia) + " em linha reta. A rota "
                    + "segura calcula caminhos de até 20 km; escolha um destino mais perto.");
        }
    }

    RotaSeguraResponse calcularAteSemCache(double lat, double lon, double latD, double lonD, ModoRota modo) {
        return calcularAteSemCache(lat, lon, latD, lonD, modo, ProgressoRota.NENHUM);
    }

    RotaSeguraResponse calcularAteSemCache(double lat, double lon, double latD, double lonD, ModoRota modo,
                                           ProgressoRota progresso) {
        double distancia = Geo.distanciaMetros(lat, lon, latD, lonD);
        int raio = (int) Math.min(12_000, distancia / 2 + 1_500);
        double latMeio = (lat + latD) / 2;
        double lonMeio = (lon + lonD) / 2;
        List<double[]> pontos = List.of(new double[]{lat, lon}, new double[]{latD, lonD});
        Andamento a = new Andamento(progresso, true);
        a.plano();
        a.iniciar(Etapa.ALTURA, "partida e destino");
        a.iniciar(Etapa.SETORES, "num raio de " + formatarDistancia(raio) + " em volta do trajeto");
        CompletableFuture<Dado<List<Double>>> futuroElevacao = CompletableFuture.supplyAsync(
                () -> a.terminarAltura(Dado.coletar(() -> elevacao.elevacoes(pontos))), executor);
        CompletableFuture<Dado<List<SetorRisco>>> futuroSetores = CompletableFuture.supplyAsync(
                () -> a.terminarSetores(Dado.coletar(() -> sgb.buscarProximos(latMeio, lonMeio, raio))), executor);
        Dado<List<Double>> elevacoes = futuroElevacao.join();
        Dado<List<SetorRisco>> setores = futuroSetores.join();

        Montador m = new Montador(modo, a);
        List<SetorRisco> hidrologicos = setoresHidrologicos(m, setores);
        Double e0 = null;
        Double eD = null;
        if (elevacoes.disponivel() && elevacoes.valor().size() >= 2) {
            fontes.registrarAcesso("COPERNICUS_DEM", elevacoes.obtidoEm());
            m.fontes.add("Altura do terreno: Copernicus DEM GLO-90 (via Open-Meteo)");
            e0 = elevacoes.valor().get(0);
            eD = elevacoes.valor().get(1);
        }
        Ponto origem = new Ponto(lat, lon, e0 == null ? null : arred(e0));

        SetorRisco setorDaOrigem = setorQueContem(hidrologicos, lat, lon);
        if (setorDaOrigem != null) {
            m.avisos.add("O ponto de partida está dentro de uma área de risco de " + tipologia(setorDaOrigem)
                    + " mapeada pelo SGB" + descricaoSetor(setorDaOrigem) + ". Saia dela o quanto antes.");
        }
        SetorRisco setorDoDestino = setorQueContem(hidrologicos, latD, lonD);
        if (setorDoDestino != null) {
            m.avisos.add("O destino escolhido fica dentro de uma área de risco de " + tipologia(setorDoDestino)
                    + " mapeada pelo SGB" + descricaoSetor(setorDoDestino) + ". Em caso de enchente, prefira um local "
                    + "mais alto (deixe o destino como \"terreno mais alto\").");
        }
        Double ganho = e0 == null || eD == null ? null : eD - e0;
        if (ganho != null && ganho < -TOLERANCIA_TRECHO_BAIXO) {
            m.avisos.add("O destino fica cerca de " + Math.round(-ganho) + " m mais baixo que o ponto de partida. "
                    + "Em caso de enchente, prefira um terreno mais alto.");
        }
        String descricao = "Destino escolhido, a " + formatarDistancia(distancia) + " em linha reta"
                + (ganho == null ? "." : Math.abs(ganho) < 1 ? ", na mesma altura da partida."
                : ", cerca de " + Math.round(Math.abs(ganho)) + " m mais " + (ganho > 0 ? "alto" : "baixo") + " que a partida.");
        Destino destino = new Destino(latD, lonD, eD == null ? null : arred(eD), ganho == null ? null : arred(ganho),
                Math.round(distancia), descricao, true);

        Candidato alvo = new Candidato(latD, lonD, eD == null ? 0 : eD, ganho == null ? 0 : ganho, distancia);
        Evitar evitar = poligonosAEvitar(hidrologicos, lat, lon, alvo);
        Rota rota = null;
        boolean desvioAplicado = true;
        if (!evitar.aneis().isEmpty()) {
            a.iniciar(Etapa.CAMINHO, detalheDesvio(evitar));
            rota = tentarRota(a, lat, lon, alvo, modo, evitar.aneis());
        }
        if (rota == null) {
            a.iniciar(Etapa.CAMINHO, evitar.aneis().isEmpty() ? detalheDesvio(evitar)
                    : "nova tentativa, sem o desvio (a anterior não deu certo)");
            rota = tentarRota(a, lat, lon, alvo, modo, List.of());
            desvioAplicado = evitar.aneis().isEmpty();
        }
        if (rota == null) {
            a.falhar(Etapa.CAMINHO, "o serviço de rotas não conseguiu traçar o caminho agora");
            m.destino = destino;
            return m.semRota("ROTA_INDISPONIVEL", origem, "O serviço de rotas não conseguiu traçar o caminho até o destino "
                    + "agora. Tente novamente em instantes.");
        }
        registrarRoteador(m, desvioAplicado, evitar);
        List<SetorRisco> conhecidos = new ArrayList<>();
        if (setorDaOrigem != null) conhecidos.add(setorDaOrigem);
        if (setorDoDestino != null) conhecidos.add(setorDoDestino);
        return montarComRota(m, origem, e0, destino, rota, desvioAplicado, desvioAplicado ? evitar.aneis().size() : 0,
                hidrologicos, conhecidos, "até o destino escolhido.");
    }

    private List<SetorRisco> setoresHidrologicos(Montador m, Dado<List<SetorRisco>> setores) {
        List<SetorRisco> hidrologicos = new ArrayList<>();
        if (setores.disponivel()) {
            fontes.registrarAcesso("SGB_SETORES_RISCO", setores.obtidoEm());
            m.fontes.add("Áreas de risco: Serviço Geológico do Brasil (setorização de riscos)");
            setores.valor().stream().filter(SetorRisco::hidrologico).forEach(hidrologicos::add);
            hidrologicos.forEach(s -> m.setores.add(new SetorMapa(s.numeroSetor(), s.grauRisco(), s.tipologia(), s.aneis())));
        } else {
            m.avisos.add("Não foi possível consultar as áreas de risco do Serviço Geológico do Brasil agora; "
                    + "o caminho abaixo não desvia delas.");
        }
        return hidrologicos;
    }

    private void registrarRoteador(Montador m, boolean desvioAplicado, Evitar evitar) {
        fontes.registrarAcesso("VALHALLA_ROTAS", Instant.now());
        m.fontes.add("Caminho: Valhalla (FOSSGIS) com dados de vias © colaboradores do OpenStreetMap");
        if (!desvioAplicado) {
            m.avisos.add("Não foi possível calcular um caminho que desvie das áreas de risco mapeadas; "
                    + "o trajeto abaixo pode passar por elas.");
        } else if (evitar.omitidos() > 0) {
            m.avisos.add("Há muitas áreas de risco na região; o desvio considerou as " + evitar.aneis().size()
                    + " mais próximas do caminho.");
        }
    }

    RotaSeguraResponse calcularSemCache(double lat, double lon, ModoRota modo) {
        return calcularSemCache(lat, lon, modo, ProgressoRota.NENHUM);
    }

    RotaSeguraResponse calcularSemCache(double lat, double lon, ModoRota modo, ProgressoRota progresso) {
        List<double[]> grade = gradeDeBusca(lat, lon);
        Andamento a = new Andamento(progresso, false);
        a.plano();
        a.iniciar(Etapa.ALTURA, grade.size() + " pontos num raio de " + formatarDistancia(DISTANCIAS_METROS[DISTANCIAS_METROS.length - 1]));
        a.iniciar(Etapa.SETORES, "num raio de " + formatarDistancia(RAIO_SETORES));
        CompletableFuture<Dado<List<Double>>> futuroElevacao = CompletableFuture.supplyAsync(
                () -> a.terminarAltura(Dado.coletar(() -> elevacao.elevacoes(grade))), executor);
        CompletableFuture<Dado<List<SetorRisco>>> futuroSetores = CompletableFuture.supplyAsync(
                () -> a.terminarSetores(Dado.coletar(() -> sgb.buscarProximos(lat, lon, RAIO_SETORES))), executor);
        Dado<List<Double>> elevacoes = futuroElevacao.join();
        Dado<List<SetorRisco>> setores = futuroSetores.join();

        Montador m = new Montador(modo, a);
        List<SetorRisco> hidrologicos = setoresHidrologicos(m, setores);

        if (!elevacoes.disponivel() || elevacoes.valor().isEmpty() || elevacoes.valor().get(0) == null) {
            return m.semRota("ROTA_INDISPONIVEL", new Ponto(lat, lon, null),
                    "Não foi possível obter a altura do terreno agora, então não dá para indicar um local mais alto. "
                            + "Tente novamente em instantes. Em emergência, ligue 199 (Defesa Civil) ou 193 (Bombeiros).");
        }
        fontes.registrarAcesso("COPERNICUS_DEM", elevacoes.obtidoEm());
        m.fontes.add("Altura do terreno: Copernicus DEM GLO-90 (via Open-Meteo)");
        double e0 = elevacoes.valor().get(0);
        Ponto origem = new Ponto(lat, lon, arred(e0));

        SetorRisco setorDaOrigem = setorQueContem(hidrologicos, lat, lon);
        if (setorDaOrigem != null) {
            m.avisos.add("O ponto de partida está dentro de uma área de risco de " + tipologia(setorDaOrigem)
                    + " mapeada pelo SGB" + descricaoSetor(setorDaOrigem) + ". Saia dela o quanto antes.");
        }

        List<Candidato> candidatos = candidatos(grade, elevacoes.valor(), e0, hidrologicos, lat, lon);
        if (candidatos.isEmpty()) {
            Double maisAltoPerto = null;
            for (int i = 1; i < grade.size(); i++) {
                Double e = elevacoes.valor().get(i);
                double[] p = grade.get(i);
                if (e != null && Geo.distanciaMetros(lat, lon, p[0], p[1]) <= RAIO_VIZINHANCA) {
                    maisAltoPerto = maisAltoPerto == null ? e : Math.max(maisAltoPerto, e);
                }
            }
            if (maisAltoPerto == null || e0 >= maisAltoPerto - TOLERANCIA_TRECHO_BAIXO) {
                return m.semRota("JA_EM_TERRENO_ALTO", origem,
                        "Este ponto já está entre os mais altos da vizinhança: num raio de 1,2 km nenhum ponto analisado "
                                + "fica mais de 2 m acima dele. Em caso de alagamento, permaneça em local alto e seguro e "
                                + "siga as orientações da Defesa Civil (199).");
            }
            return m.semRota("SEM_TERRENO_ALTO", origem,
                    "Não encontramos terreno pelo menos 5 m mais alto e fora de áreas de risco num raio de 3 km. "
                            + "Em emergência, procure o andar mais alto de um prédio sólido próximo e ligue 199 (Defesa Civil).");
        }

        int chamadas = 0;
        List<Candidato> testados = new ArrayList<>();
        for (Candidato c : candidatos) {
            if (testados.size() >= MAX_DESTINOS_TESTADOS || chamadas >= MAX_CHAMADAS_ROTEADOR) {
                break;
            }
            if (testados.stream().anyMatch(t -> Geo.distanciaMetros(t.lat(), t.lon(), c.lat(), c.lon()) < 200)) {
                continue; // muito perto de um destino que já falhou
            }
            testados.add(c);
            // A partir do segundo ponto alto, a pessoa vê que outro destino está sendo testado
            String prefixo = testados.size() == 1 ? ""
                    : "testando outro ponto alto (" + testados.size() + " de " + MAX_DESTINOS_TESTADOS + "): ";
            Evitar evitar = poligonosAEvitar(hidrologicos, lat, lon, c);
            Rota rota = null;
            boolean desvioAplicado = true;
            if (!evitar.aneis().isEmpty()) {
                chamadas++;
                a.iniciar(Etapa.CAMINHO, prefixo + detalheDesvio(evitar));
                rota = tentarRota(a, lat, lon, c, modo, evitar.aneis());
            }
            if (rota == null && chamadas < MAX_CHAMADAS_ROTEADOR) {
                chamadas++;
                a.iniciar(Etapa.CAMINHO, prefixo + (evitar.aneis().isEmpty() ? detalheDesvio(evitar)
                        : "nova tentativa, sem o desvio (a anterior não deu certo)"));
                rota = tentarRota(a, lat, lon, c, modo, List.of());
                desvioAplicado = evitar.aneis().isEmpty();
            }
            if (rota != null) {
                registrarRoteador(m, desvioAplicado, evitar);
                candidatos.stream().filter(o -> o != c).limit(3)
                        .forEach(o -> m.alternativas.add(new Ponto(o.lat(), o.lon(), arred(o.elevacao()))));
                return montarComRota(m, origem, e0, destino(lat, lon, c), rota, desvioAplicado,
                        desvioAplicado ? evitar.aneis().size() : 0, hidrologicos,
                        setorDaOrigem == null ? List.of() : List.of(setorDaOrigem),
                        "até um terreno cerca de " + Math.round(c.ganho()) + " m mais alto que o ponto de partida.");
            }
        }
        a.falhar(Etapa.CAMINHO, "o serviço de rotas não conseguiu traçar o caminho agora");
        Candidato melhor = candidatos.get(0);
        m.destino = destino(lat, lon, melhor);
        return m.semRota("ROTA_INDISPONIVEL", origem,
                "Há terreno cerca de " + Math.round(melhor.ganho()) + " m mais alto a " + Math.round(melhor.distancia())
                        + " m a " + Geo.direcao(Geo.rumo(lat, lon, melhor.lat(), melhor.lon()))
                        + " (marcado no mapa), mas o serviço de rotas não conseguiu traçar o caminho agora. "
                        + "Tente novamente em instantes ou use o mapa como referência.");
    }

    private Rota tentarRota(Andamento a, double lat, double lon, Candidato c, ModoRota modo, List<List<double[]>> evitar) {
        a.verificarCancelamento();
        try {
            return roteador.rota(lat, lon, c.lat(), c.lon(), modo, evitar);
        } catch (IntegracaoExternaException e) {
            log.info("Rota até {},{} falhou ({} polígonos evitados): {}", c.lat(), c.lon(), evitar.size(), e.getMessage());
            return null;
        }
    }

    /**
     * @param setoresConhecidos setores da partida/destino, já avisados: não contam como "atravessados"
     * @param finalMensagem     complemento de "Siga por X (cerca de Y a pé) ..."
     */
    private RotaSeguraResponse montarComRota(Montador m, Ponto origem, Double e0, Destino destino, Rota rota,
                                             boolean desvioAplicado, int setoresEvitados, List<SetorRisco> hidrologicos,
                                             List<SetorRisco> setoresConhecidos, String finalMensagem) {
        m.andamento.concluir(Etapa.CAMINHO, formatarDistancia(rota.distanciaMetros()) + " pelas ruas");
        m.destino = destino;
        List<RotaSeguraResponse.Instrucao> instrucoes = rota.instrucoes().stream()
                .filter(i -> i.texto() != null)
                .map(i -> new RotaSeguraResponse.Instrucao(i.texto(), Math.round(i.distanciaMetros()), Math.round(i.duracaoSegundos())))
                .toList();
        m.trajeto = new Trajeto(Math.round(rota.distanciaMetros()), Math.round(rota.duracaoSegundos()), rota.pontos(),
                instrucoes, desvioAplicado, setoresEvitados);

        // Setores de risco atravessados pelo caminho (exceto o da própria origem)
        Set<String> atravessados = new LinkedHashSet<>();
        for (double[] p : Geo.amostrar(rota.pontos(), Math.min(200, Math.max(20, rota.pontos().size())))) {
            SetorRisco s = setorQueContem(hidrologicos, p[0], p[1]);
            if (s != null && !setoresConhecidos.contains(s)) {
                atravessados.add(s.numeroSetor() == null ? "sem número" : s.numeroSetor());
            }
        }
        if (!atravessados.isEmpty()) {
            m.avisos.add("O caminho passa por dentro de área de risco mapeada pelo SGB (setor " + String.join(", ", atravessados)
                    + "). Atenção redobrada nesse trecho e não atravesse se houver água.");
        }

        // Perfil de elevação do caminho
        List<double[]> amostras = Geo.amostrar(rota.pontos(), AMOSTRAS_PERFIL);
        m.andamento.verificarCancelamento();
        m.andamento.iniciar(Etapa.PERFIL, amostras.size() + " pontos do caminho");
        Dado<List<Double>> elevPerfil = Dado.coletar(() -> elevacao.elevacoes(amostras.stream().map(a -> new double[]{a[0], a[1]}).toList()));
        if (!elevPerfil.disponivel()) {
            m.andamento.falhar(Etapa.PERFIL, "não foi possível conferir agora; o caminho continua valendo");
        }
        if (elevPerfil.disponivel()) {
            List<double[]> perfil = new ArrayList<>();
            double min = Double.MAX_VALUE;
            double max = -Double.MAX_VALUE;
            double[] pontoMin = null;
            double distMin = 0;
            for (int i = 0; i < amostras.size(); i++) {
                Double e = elevPerfil.valor().get(i);
                if (e == null) {
                    continue;
                }
                perfil.add(new double[]{Math.round(amostras.get(i)[2]), arred(e)});
                if (e < min) {
                    min = e;
                    pontoMin = new double[]{amostras.get(i)[0], amostras.get(i)[1]};
                    distMin = amostras.get(i)[2];
                }
                max = Math.max(max, e);
            }
            if (pontoMin == null) {
                m.andamento.falhar(Etapa.PERFIL, "o serviço não trouxe a altura dos pontos; o caminho continua valendo");
            } else {
                m.andamento.concluir(Etapa.PERFIL, "trecho mais baixo a " + Math.round(min) + " m de altitude");
                m.perfil = new Perfil(arred(min), arred(max), Math.round(distMin), pontoMin, perfil);
                if (e0 != null && min < e0 - TOLERANCIA_TRECHO_BAIXO && distMin > 50) {
                    m.avisos.add("Atenção: a cerca de " + Math.round(distMin) + " m do início, o caminho passa por um trecho "
                            + Math.round(e0 - min) + " m mais baixo que o ponto de partida (marcado no mapa). Se esse trecho "
                            + "estiver alagado, não atravesse: procure outro caminho ou um andar alto de um prédio próximo.");
                }
            }
        }

        String tempo = formatarTempo(rota.duracaoSegundos());
        String mensagem = "Siga por " + formatarDistancia(rota.distanciaMetros()) + " (cerca de " + tempo + " "
                + (m.modo == ModoRota.A_PE ? "a pé" : "de carro") + ") " + finalMensagem;
        return m.comRota(origem, mensagem);
    }

    // ------------------------------------------------------------------ busca de destino

    static List<double[]> gradeDeBusca(double lat, double lon) {
        List<double[]> grade = new ArrayList<>();
        grade.add(new double[]{lat, lon});
        for (int a = 0; a < DISTANCIAS_METROS.length; a++) {
            double defasagem = (a % 2 == 1) ? 360.0 / RUMOS / 2 : 0; // anéis alternados para cobrir melhor
            for (int r = 0; r < RUMOS; r++) {
                grade.add(Geo.deslocar(lat, lon, DISTANCIAS_METROS[a], r * 360.0 / RUMOS + defasagem));
            }
        }
        return grade;
    }

    /** Candidatos a destino ordenados: primeiro os 10 m+ mais altos, depois os 5 m+, cada grupo do mais perto ao mais longe. */
    static List<Candidato> candidatos(List<double[]> grade, List<Double> elevacoes, double e0, List<SetorRisco> hidrologicos,
                                      double lat, double lon) {
        List<Candidato> lista = new ArrayList<>();
        for (int i = 1; i < grade.size() && i < elevacoes.size(); i++) {
            Double e = elevacoes.get(i);
            if (e == null) {
                continue;
            }
            double ganho = e - e0;
            double[] p = grade.get(i);
            if (ganho < DESNIVEL_MINIMO || setorQueContem(hidrologicos, p[0], p[1]) != null) {
                continue;
            }
            lista.add(new Candidato(p[0], p[1], e, ganho, Geo.distanciaMetros(lat, lon, p[0], p[1])));
        }
        lista.sort(Comparator.comparing((Candidato c) -> c.ganho() >= DESNIVEL_IDEAL ? 0 : 1)
                .thenComparingDouble(Candidato::distancia)
                .thenComparing(Comparator.comparingDouble(Candidato::ganho).reversed()));
        return lista;
    }

    record Candidato(double lat, double lon, double elevacao, double ganho, double distancia) {
    }

    record Evitar(List<List<double[]>> aneis, int omitidos) {
    }

    /**
     * Setores a evitar: todos os hidrológicos que não contêm a origem nem o destino, dos mais próximos
     * do trajeto em linha reta para os mais distantes, até o limite de perímetro aceito pelo roteador.
     */
    static Evitar poligonosAEvitar(List<SetorRisco> hidrologicos, double lat, double lon, Candidato destino) {
        record Anel(List<double[]> pontos, double distancia, double perimetro) {
        }
        List<double[]> segmento = List.of(new double[]{lat, lon}, new double[]{destino.lat(), destino.lon()});
        List<Anel> aneis = new ArrayList<>();
        for (SetorRisco s : hidrologicos) {
            boolean contemExtremo = false;
            for (List<double[]> anel : s.aneis()) {
                if (Geo.dentroDoPoligono(lat, lon, anel) || Geo.dentroDoPoligono(destino.lat(), destino.lon(), anel)) {
                    contemExtremo = true;
                }
            }
            if (contemExtremo) {
                continue;
            }
            for (List<double[]> anel : s.aneis()) {
                double[] centro = centroide(anel);
                double[] maisPerto = Geo.pontoMaisProximo(centro[0], centro[1], segmento);
                List<double[]> fechado = new ArrayList<>(anel);
                if (!java.util.Arrays.equals(fechado.get(0), fechado.get(fechado.size() - 1))) {
                    fechado.add(fechado.get(0));
                }
                aneis.add(new Anel(anel, maisPerto == null ? Double.MAX_VALUE : maisPerto[2], Geo.comprimento(fechado)));
            }
        }
        aneis.sort(Comparator.comparingDouble(Anel::distancia));
        List<List<double[]>> escolhidos = new ArrayList<>();
        double total = 0;
        int omitidos = 0;
        for (Anel a : aneis) {
            if (total + a.perimetro() <= PERIMETRO_MAXIMO_EVITAR) {
                escolhidos.add(a.pontos());
                total += a.perimetro();
            } else {
                omitidos++;
            }
        }
        return new Evitar(escolhidos, omitidos);
    }

    static SetorRisco setorQueContem(List<SetorRisco> setores, double lat, double lon) {
        for (SetorRisco s : setores) {
            for (List<double[]> anel : s.aneis()) {
                if (Geo.dentroDoPoligono(lat, lon, anel)) {
                    return s;
                }
            }
        }
        return null;
    }

    private static double[] centroide(List<double[]> anel) {
        double la = 0;
        double lo = 0;
        for (double[] p : anel) {
            la += p[0];
            lo += p[1];
        }
        return new double[]{la / anel.size(), lo / anel.size()};
    }

    private static Destino destino(double lat, double lon, Candidato c) {
        String descricao = "Terreno cerca de " + Math.round(c.ganho()) + " m mais alto que o ponto de partida, a "
                + Math.round(c.distancia()) + " m a " + Geo.direcao(Geo.rumo(lat, lon, c.lat(), c.lon())) + " em linha reta, "
                + "fora das áreas de risco mapeadas.";
        return new Destino(c.lat(), c.lon(), arred(c.elevacao()), arred(c.ganho()), Math.round(c.distancia()), descricao, false);
    }

    private static String tipologia(SetorRisco s) {
        return s.tipologia() == null ? "inundação" : s.tipologia().toLowerCase(Locale.ROOT);
    }

    private static String descricaoSetor(SetorRisco s) {
        List<String> partes = new ArrayList<>();
        if (s.numeroSetor() != null) partes.add("setor " + s.numeroSetor());
        if (s.grauRisco() != null) partes.add("grau " + s.grauRisco().toLowerCase(Locale.ROOT));
        return partes.isEmpty() ? "" : " (" + String.join(", ", partes) + ")";
    }

    static String formatarDistancia(double metros) {
        if (metros < 1000) {
            return Math.round(metros / 10.0) * 10 + " m";
        }
        // "3 km" em vez de "3,0 km"
        return String.format(Locale.of("pt", "BR"), "%.1f km", metros / 1000).replace(",0 km", " km");
    }

    static String formatarTempo(double segundos) {
        long min = Math.max(1, Math.round(segundos / 60));
        return min < 60 ? min + " min" : (min / 60) + " h " + (min % 60) + " min";
    }

    private static double arred(double v) {
        return Math.round(v * 10) / 10.0;
    }

    static String detalheDesvio(Evitar evitar) {
        int n = evitar.aneis().size();
        if (n == 0) {
            return "traçando o caminho";
        }
        return "desviando de " + n + (n == 1 ? " área" : " áreas") + " de risco";
    }

    /** Envia o andamento das etapas para quem acompanha o cálculo (a tela, via {@link ProgressoRota}). */
    static final class Andamento {
        private final ProgressoRota progresso;
        private final boolean destinoEscolhido;

        Andamento(ProgressoRota progresso, boolean destinoEscolhido) {
            this.progresso = progresso;
            this.destinoEscolhido = destinoEscolhido;
        }

        /** Todas as etapas como pendentes, para a tela já mostrar o que vem pela frente. */
        void plano() {
            for (Etapa e : Etapa.values()) {
                informar(e, Estado.PENDENTE, null);
            }
        }

        void iniciar(Etapa etapa, String detalhe) {
            informar(etapa, Estado.EM_ANDAMENTO, detalhe);
        }

        void concluir(Etapa etapa, String detalhe) {
            informar(etapa, Estado.CONCLUIDA, detalhe);
        }

        void falhar(Etapa etapa, String detalhe) {
            informar(etapa, Estado.FALHOU, detalhe);
        }

        Dado<List<Double>> terminarAltura(Dado<List<Double>> d) {
            long comAltura = d.disponivel() ? d.valor().stream().filter(java.util.Objects::nonNull).count() : 0;
            if (comAltura == 0) {
                falhar(Etapa.ALTURA, "o serviço de altitude não respondeu");
            } else {
                concluir(Etapa.ALTURA, comAltura + (comAltura == 1 ? " ponto analisado" : " pontos analisados"));
            }
            return d;
        }

        Dado<List<SetorRisco>> terminarSetores(Dado<List<SetorRisco>> d) {
            if (!d.disponivel()) {
                falhar(Etapa.SETORES, "o serviço não respondeu; o caminho não vai desviar das áreas de risco");
                return d;
            }
            long n = d.valor().stream().filter(SetorRisco::hidrologico).count();
            concluir(Etapa.SETORES, n == 0 ? "nenhuma área de risco de inundação mapeada"
                    : n + (n == 1 ? " área de risco de inundação encontrada" : " áreas de risco de inundação encontradas"));
            return d;
        }

        /** Para o cálculo se a pessoa desistiu, antes de mais uma consulta externa. */
        void verificarCancelamento() {
            if (progresso.cancelado()) {
                throw new CalculoCanceladoException();
            }
        }

        private void informar(Etapa etapa, Estado estado, String detalhe) {
            try {
                progresso.informar(new ProgressoRota.Evento(etapa.name(), estado.name(), etapa.rotulo(destinoEscolhido),
                        etapa.fonte(), detalhe));
            } catch (RuntimeException e) {
                // o andamento é só informativo: uma falha ao avisar a tela não interrompe o cálculo
                log.debug("Falha ao informar andamento da rota: {}", e.getMessage());
            }
        }
    }

    /** Acumula as partes da resposta. */
    private static final class Montador {
        final ModoRota modo;
        final Andamento andamento;
        final List<String> avisos = new ArrayList<>();
        final List<String> fontes = new ArrayList<>();
        final List<SetorMapa> setores = new ArrayList<>();
        final List<Ponto> alternativas = new ArrayList<>();
        Destino destino;
        Trajeto trajeto;
        Perfil perfil;

        Montador(ModoRota modo, Andamento andamento) {
            this.modo = modo;
            this.andamento = andamento;
        }

        private List<String> avisosFinais() {
            List<String> lista = new ArrayList<>(avisos);
            lista.add("Esta é uma sugestão calculada automaticamente com dados públicos, não uma rota oficial de evacuação: "
                    + "a altura do terreno tem margem de erro de alguns metros e as ruas podem estar alagadas ou bloqueadas.");
            lista.add("Nunca atravesse ruas alagadas, a pé ou de carro. Siga sempre as orientações da Defesa Civil (199) "
                    + "e dos Bombeiros (193).");
            return lista;
        }

        RotaSeguraResponse semRota(String status, Ponto origem, String mensagem) {
            return new RotaSeguraResponse(status, mensagem, modo.name(), modo.getRotulo(), origem, destino, null, null,
                    setores, alternativas, avisosFinais(), List.copyOf(new LinkedHashSet<>(fontes)), Instant.now());
        }

        RotaSeguraResponse comRota(Ponto origem, String mensagem) {
            return new RotaSeguraResponse("ROTA_ENCONTRADA", mensagem, modo.name(), modo.getRotulo(), origem, destino,
                    trajeto, perfil, setores, alternativas, avisosFinais(), List.copyOf(new LinkedHashSet<>(fontes)),
                    Instant.now());
        }
    }
}
