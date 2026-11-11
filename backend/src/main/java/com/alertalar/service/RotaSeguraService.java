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
 * Rota segura até um destino escolhido pela pessoa (endereço, nome do local ou ponto no mapa):
 * calcula o caminho pelas ruas, a pé ou de carro, pedindo ao roteador que evite os setores de
 * risco hidrológico do SGB entre a partida e o destino.
 *
 * <p>Depois confere o caminho: setores que ele ainda atravessa e o perfil de altura (trechos mais
 * baixos que a partida geram aviso). Também avisa se o destino fica dentro de uma área de risco
 * ou mais baixo que a partida. É uma sugestão baseada em dados públicos, não uma rota oficial de
 * evacuação.</p>
 */
@Service
public class RotaSeguraService {

    private static final Logger log = LoggerFactory.getLogger(RotaSeguraService.class);

    /** O Valhalla limita o perímetro total dos polígonos evitados (padrão de 10 km). */
    static final double PERIMETRO_MAXIMO_EVITAR = 9000;
    static final int AMOSTRAS_PERFIL = 60;
    static final double TOLERANCIA_TRECHO_BAIXO = 2;
    /** Distância máxima em linha reta até o destino (o desvio de áreas de risco tem limite de perímetro). */
    static final double DISTANCIA_MAXIMA_DESTINO = 20_000;
    static final double DISTANCIA_MINIMA_DESTINO = 30;
    static final String SEM_PERFIL = "Não foi possível consultar a altura do terreno ao longo do caminho agora, então "
            + "ele não foi conferido quanto a trechos mais baixos. Fique atento a ruas em baixada.";

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

    public RotaSeguraResponse calcular(double lat, double lon, double latDestino, double lonDestino, ModoRota modo) {
        return calcular(lat, lon, latDestino, lonDestino, modo, ProgressoRota.NENHUM);
    }

    /** Rota até o destino escolhido, avisando o andamento de cada consulta externa. */
    public RotaSeguraResponse calcular(double lat, double lon, double latDestino, double lonDestino, ModoRota modo,
                                       ProgressoRota progresso) {
        validar(lat, lon, latDestino, lonDestino);
        String chave = String.format(Locale.ROOT, "%.4f,%.4f>%.4f,%.4f,%s", lat, lon, latDestino, lonDestino, modo);
        return cache.obterOuCalcular(chave, () -> calcularSemCache(lat, lon, latDestino, lonDestino, modo, progresso));
    }

    /** Confere partida e destino antes de começar (o erro volta como resposta comum, não no meio do andamento). */
    public void validar(double lat, double lon, double latDestino, double lonDestino) {
        if (!Geo.dentroDoBrasil(lat, lon) || !Geo.dentroDoBrasil(latDestino, lonDestino)) {
            throw new RegraNegocioException("A partida e o destino precisam estar no território brasileiro.");
        }
        double distancia = Geo.distanciaMetros(lat, lon, latDestino, lonDestino);
        if (distancia < DISTANCIA_MINIMA_DESTINO) {
            throw new RegraNegocioException("O destino está no mesmo lugar da partida. Escolha outro destino.");
        }
        if (distancia > DISTANCIA_MAXIMA_DESTINO) {
            throw new RegraNegocioException("O destino está a " + formatarDistancia(distancia) + " em linha reta. A rota "
                    + "segura calcula caminhos de até 20 km; escolha um destino mais perto.");
        }
    }

    private RotaSeguraResponse calcularSemCache(double lat, double lon, double latD, double lonD, ModoRota modo,
                                                ProgressoRota progresso) {
        double distancia = Geo.distanciaMetros(lat, lon, latD, lonD);
        int raio = (int) Math.min(12_000, distancia / 2 + 1_500);
        double latMeio = (lat + latD) / 2;
        double lonMeio = (lon + lonD) / 2;
        List<double[]> pontos = List.of(new double[]{lat, lon}, new double[]{latD, lonD});
        Andamento a = new Andamento(progresso);
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
                    + " mapeada pelo SGB" + descricaoSetor(setorDoDestino) + ". Em caso de enchente, prefira um lugar "
                    + "mais alto e fora das áreas de risco.");
        }
        Double ganho = e0 == null || eD == null ? null : eD - e0;
        if (ganho != null && ganho < -TOLERANCIA_TRECHO_BAIXO) {
            m.avisos.add("O destino fica cerca de " + Math.round(-ganho) + " m mais baixo que o ponto de partida. "
                    + "Em caso de enchente, prefira um lugar mais alto.");
        }
        String descricao = "Destino escolhido, a " + formatarDistancia(distancia) + " em linha reta"
                + (ganho == null ? "." : Math.abs(ganho) < 1 ? ", na mesma altura da partida."
                : ", cerca de " + Math.round(Math.abs(ganho)) + " m mais " + (ganho > 0 ? "alto" : "baixo") + " que a partida.");
        Destino destino = new Destino(latD, lonD, eD == null ? null : arred(eD), ganho == null ? null : arred(ganho),
                Math.round(distancia), descricao);

        Evitar evitar = poligonosAEvitar(hidrologicos, lat, lon, latD, lonD);
        Rota rota = null;
        boolean desvioAplicado = true;
        if (!evitar.aneis().isEmpty()) {
            a.iniciar(Etapa.CAMINHO, detalheDesvio(evitar));
            rota = tentarRota(a, lat, lon, latD, lonD, modo, evitar.aneis());
        }
        if (rota == null) {
            a.iniciar(Etapa.CAMINHO, evitar.aneis().isEmpty() ? detalheDesvio(evitar)
                    : "nova tentativa, sem o desvio (a anterior não deu certo)");
            rota = tentarRota(a, lat, lon, latD, lonD, modo, List.of());
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
                hidrologicos, conhecidos);
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

    private Rota tentarRota(Andamento a, double lat, double lon, double latD, double lonD, ModoRota modo,
                            List<List<double[]>> evitar) {
        a.verificarCancelamento();
        try {
            return roteador.rota(lat, lon, latD, lonD, modo, evitar);
        } catch (IntegracaoExternaException e) {
            log.info("Rota até {},{} falhou ({} polígonos evitados): {}", latD, lonD, evitar.size(), e.getMessage());
            return null;
        }
    }

    /**
     * @param setoresConhecidos setores da partida/destino, já avisados: não contam como "atravessados"
     */
    private RotaSeguraResponse montarComRota(Montador m, Ponto origem, Double e0, Destino destino, Rota rota,
                                             boolean desvioAplicado, int setoresEvitados, List<SetorRisco> hidrologicos,
                                             List<SetorRisco> setoresConhecidos) {
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
            m.avisos.add(SEM_PERFIL);
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
                m.avisos.add(SEM_PERFIL);
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
                + (m.modo == ModoRota.A_PE ? "a pé" : "de carro") + ") até o destino escolhido.";
        return m.comRota(origem, mensagem);
    }

    // ------------------------------------------------------------------ áreas a evitar

    record Evitar(List<List<double[]>> aneis, int omitidos) {
    }

    /**
     * Setores a evitar: todos os hidrológicos que não contêm a origem nem o destino, dos mais próximos
     * do trajeto em linha reta para os mais distantes, até o limite de perímetro aceito pelo roteador.
     */
    static Evitar poligonosAEvitar(List<SetorRisco> hidrologicos, double lat, double lon, double latD, double lonD) {
        record Anel(List<double[]> pontos, double distancia, double perimetro) {
        }
        List<double[]> segmento = List.of(new double[]{lat, lon}, new double[]{latD, lonD});
        List<Anel> aneis = new ArrayList<>();
        for (SetorRisco s : hidrologicos) {
            boolean contemExtremo = false;
            for (List<double[]> anel : s.aneis()) {
                if (Geo.dentroDoPoligono(lat, lon, anel) || Geo.dentroDoPoligono(latD, lonD, anel)) {
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

        Andamento(ProgressoRota progresso) {
            this.progresso = progresso;
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
                progresso.informar(new ProgressoRota.Evento(etapa.name(), estado.name(), etapa.rotulo(),
                        etapa.fonte(), detalhe));
            } catch (RuntimeException e) {
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
                    setores, avisosFinais(), List.copyOf(new LinkedHashSet<>(fontes)), Instant.now());
        }

        RotaSeguraResponse comRota(Ponto origem, String mensagem) {
            return new RotaSeguraResponse("ROTA_ENCONTRADA", mensagem, modo.name(), modo.getRotulo(), origem, destino,
                    trajeto, perfil, setores, avisosFinais(), List.copyOf(new LinkedHashSet<>(fontes)),
                    Instant.now());
        }
    }
}