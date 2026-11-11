package com.alertalar.service;

import com.alertalar.dto.RotaSeguraResponse;
import com.alertalar.exception.IntegracaoExternaException;
import com.alertalar.exception.RegraNegocioException;
import com.alertalar.integration.openmeteo.ElevacaoClient;
import com.alertalar.integration.sgb.SgbSetoresRiscoClient;
import com.alertalar.integration.sgb.SgbSetoresRiscoClient.SetorRisco;
import com.alertalar.integration.valhalla.ModoRota;
import com.alertalar.integration.valhalla.ValhallaClient;
import com.alertalar.integration.valhalla.ValhallaClient.Instrucao;
import com.alertalar.integration.valhalla.ValhallaClient.Rota;
import com.alertalar.util.Geo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Rota segura até o destino escolhido: desvio das áreas de risco, conferência do caminho e andamento. */
@ExtendWith(MockitoExtension.class)
class RotaSeguraServiceTest {

    private static final double LAT = -29.92;
    private static final double LON = -51.18;
    /** Destino padrão: 2 km a nordeste da partida. */
    private static final double[] DESTINO = Geo.deslocar(LAT, LON, 2000, 30);

    @Mock ElevacaoClient elevacao;
    @Mock SgbSetoresRiscoClient sgb;
    @Mock ValhallaClient roteador;
    @Mock FonteDadosService fontes;

    private RotaSeguraService service;

    @BeforeEach
    void criar() {
        service = new RotaSeguraService(elevacao, sgb, roteador, fontes);
    }

    // ------------------------------------------------------------------ apoio

    /** Altura da partida e do destino (pedido com 2 pontos) e, para o perfil do caminho, a função dada. */
    private void elevacoes(double partida, double destino, Function<double[], Double> perfil) {
        when(elevacao.elevacoes(anyList())).thenAnswer(inv -> {
            List<double[]> pontos = inv.getArgument(0);
            if (pontos.size() == 2) {
                return List.of(partida, destino);
            }
            List<Double> r = new ArrayList<>();
            pontos.forEach(p -> r.add(perfil.apply(p)));
            return r;
        });
    }

    private void roteadorEncontra() {
        when(roteador.rota(anyDouble(), anyDouble(), anyDouble(), anyDouble(), any(), anyList()))
                .thenAnswer(inv -> rotaAte(inv.getArgument(2), inv.getArgument(3)));
    }

    private static Rota rotaAte(double latD, double lonD) {
        List<double[]> pontos = List.of(new double[]{LAT, LON}, new double[]{(LAT + latD) / 2, (LON + lonD) / 2},
                new double[]{latD, lonD});
        return new Rota(pontos, List.of(
                new Instrucao("Siga ao norte na Rua de Teste.", 300, 240, 0, 2),
                new Instrucao("Você chegou ao seu destino.", 0, 0, 2, 4)), 600, 480);
    }

    /** Quadrado de lado ~2·meioLado metros centrado no ponto. */
    private static SetorRisco setor(String numero, double lat, double lon, double meioLado, boolean hidrologico) {
        double dLat = meioLado / 111_320.0;
        double dLon = meioLado / (111_320.0 * Math.cos(Math.toRadians(lat)));
        List<double[]> anel = List.of(new double[]{lat - dLat, lon - dLon}, new double[]{lat - dLat, lon + dLon},
                new double[]{lat + dLat, lon + dLon}, new double[]{lat + dLat, lon - dLon}, new double[]{lat - dLat, lon - dLon});
        return new SetorRisco("Canoas", "RS", numero, "Vila Teste", hidrologico ? "Inundação" : "Deslizamento",
                "Muito Alto", null, null, hidrologico, List.of(anel));
    }

    private RotaSeguraResponse calcular(ModoRota modo) {
        return service.calcular(LAT, LON, DESTINO[0], DESTINO[1], modo);
    }

    // ------------------------------------------------------------------ caminho

    @Test
    @DisplayName("Rota encontrada: caminho até o destino, altura, perfil, fontes e avisos padrão")
    void rotaEncontrada() {
        elevacoes(8.0, 15.0, p -> 8.0 + Geo.distanciaMetros(LAT, LON, p[0], p[1]) / 300);
        when(sgb.buscarProximos(anyDouble(), anyDouble(), anyInt())).thenReturn(List.of());
        roteadorEncontra();

        RotaSeguraResponse r = calcular(ModoRota.A_PE);

        assertEquals("ROTA_ENCONTRADA", r.status());
        assertEquals(DESTINO[0], r.destino().latitude(), 1e-9);
        assertEquals(7.0, r.destino().ganhoElevacao(), 1e-9);
        assertEquals(2000, r.destino().distanciaRetaMetros(), 3);
        assertTrue(r.destino().descricao().contains("7 m mais alto"), r.destino().descricao());
        assertEquals(8.0, r.origem().elevacao(), 1e-9);
        assertEquals(600, r.trajeto().distanciaMetros());
        assertEquals(2, r.trajeto().instrucoes().size());
        assertTrue(r.trajeto().desvioAreasRiscoAplicado());
        assertEquals("Siga por 600 m (cerca de 8 min a pé) até o destino escolhido.", r.mensagem());
        assertNotNull(r.perfil());
        assertEquals(8.0, r.perfil().elevacaoMinima(), 1e-9);
        assertTrue(r.fontes().stream().anyMatch(f -> f.contains("Valhalla")));
        assertTrue(r.fontes().stream().anyMatch(f -> f.contains("Copernicus")));
        assertTrue(r.avisos().stream().anyMatch(a -> a.contains("não uma rota oficial")));
        assertTrue(r.avisos().stream().anyMatch(a -> a.contains("Nunca atravesse ruas alagadas")));
        assertEquals(2, r.avisos().size(), "só os avisos padrão");
        verify(fontes).registrarAcesso(eq("VALHALLA_ROTAS"), any());
        verify(roteador).rota(eq(LAT), eq(LON), eq(DESTINO[0]), eq(DESTINO[1]), eq(ModoRota.A_PE), anyList());
        verify(sgb).buscarProximos(anyDouble(), anyDouble(), eq(2500)); // metade da distância + 1,5 km

        // a mesma consulta logo depois vem do cache
        calcular(ModoRota.A_PE);
        verify(roteador, times(1)).rota(anyDouble(), anyDouble(), anyDouble(), anyDouble(), any(), anyList());
    }

    @Test
    @DisplayName("Áreas de risco entre a partida e o destino vão para o roteador evitar")
    void evitaSetores() {
        elevacoes(10.0, 10.0, p -> 10.0);
        double[] meio = Geo.deslocar(LAT, LON, 1000, 30);
        double[] aoLado = Geo.deslocar(LAT, LON, 1500, 120);
        when(sgb.buscarProximos(anyDouble(), anyDouble(), anyInt())).thenReturn(List.of(
                setor("H1", meio[0], meio[1], 80, true),
                setor("H2", aoLado[0], aoLado[1], 60, true),
                setor("D1", meio[0], meio[1] + 0.003, 60, false)));
        roteadorEncontra();

        RotaSeguraResponse r = calcular(ModoRota.A_PE);

        assertEquals("ROTA_ENCONTRADA", r.status());
        assertEquals(2, r.setoresRisco().size(), "somente setores hidrológicos aparecem no mapa");
        assertEquals(2, r.trajeto().setoresEvitados());
        verify(roteador).rota(anyDouble(), anyDouble(), anyDouble(), anyDouble(), any(), argThat(l -> l.size() == 2));
    }

    @Test
    @DisplayName("Se o desvio falhar, tenta sem desvio e avisa que o caminho pode passar por áreas de risco")
    void semDesvio() {
        elevacoes(10.0, 10.0, p -> 10.0);
        double[] meio = Geo.deslocar(LAT, LON, 1000, 30);
        when(sgb.buscarProximos(anyDouble(), anyDouble(), anyInt())).thenReturn(List.of(setor("H1", meio[0], meio[1], 80, true)));
        when(roteador.rota(anyDouble(), anyDouble(), anyDouble(), anyDouble(), any(), argThat(l -> l != null && !l.isEmpty())))
                .thenThrow(new IntegracaoExternaException(ValhallaClient.FONTE, "sem caminho"));
        when(roteador.rota(anyDouble(), anyDouble(), anyDouble(), anyDouble(), any(), argThat(l -> l != null && l.isEmpty())))
                .thenAnswer(inv -> rotaAte(inv.getArgument(2), inv.getArgument(3)));

        RotaSeguraResponse r = calcular(ModoRota.CARRO);

        assertEquals("ROTA_ENCONTRADA", r.status());
        assertFalse(r.trajeto().desvioAreasRiscoAplicado());
        assertEquals(0, r.trajeto().setoresEvitados());
        assertTrue(r.avisos().stream().anyMatch(a -> a.contains("desvie das áreas de risco")));
        assertTrue(r.mensagem().contains("de carro"));
    }

    @Test
    @DisplayName("Trecho do caminho mais baixo que a partida gera aviso")
    void trechoBaixo() {
        double[] fundo = Geo.deslocar(LAT, LON, 600, 30);
        elevacoes(8.0, 9.0, p -> Geo.distanciaMetros(fundo[0], fundo[1], p[0], p[1]) < 60 ? 2.0 : 9.0);
        when(sgb.buscarProximos(anyDouble(), anyDouble(), anyInt())).thenReturn(List.of());
        when(roteador.rota(anyDouble(), anyDouble(), anyDouble(), anyDouble(), any(), anyList()))
                .thenAnswer(inv -> new Rota(List.of(new double[]{LAT, LON}, fundo,
                        new double[]{inv.getArgument(2), inv.getArgument(3)}), List.of(), 2100, 1600));

        RotaSeguraResponse r = calcular(ModoRota.A_PE);

        assertEquals(2.0, r.perfil().elevacaoMinima(), 1e-9);
        assertTrue(r.avisos().stream().anyMatch(a -> a.contains("6 m mais baixo que o ponto de partida")), r.avisos().toString());
    }

    @Test
    @DisplayName("Destino dentro de área de risco ou mais baixo que a partida gera aviso")
    void destinoArriscado() {
        double[] destino = Geo.deslocar(LAT, LON, 800, 90);
        elevacoes(12.0, 4.0, p -> 8.0);
        when(sgb.buscarProximos(anyDouble(), anyDouble(), anyInt())).thenReturn(List.of(setor("H7", destino[0], destino[1], 100, true)));
        roteadorEncontra();

        RotaSeguraResponse r = service.calcular(LAT, LON, destino[0], destino[1], ModoRota.CARRO);

        assertEquals("ROTA_ENCONTRADA", r.status());
        assertTrue(r.avisos().stream().anyMatch(a -> a.contains("O destino escolhido fica dentro de uma área de risco")
                && a.contains("H7") && a.contains("prefira um lugar mais alto")), r.avisos().toString());
        assertTrue(r.avisos().stream().anyMatch(a -> a.contains("8 m mais baixo que o ponto de partida")));
        assertTrue(r.avisos().stream().noneMatch(a -> a.contains("O caminho passa por dentro")), "setor do destino já foi avisado");
        assertTrue(r.avisos().stream().noneMatch(a -> a.contains("terreno mais alto")));
        assertEquals(-8.0, r.destino().ganhoElevacao(), 1e-9);
    }

    @Test
    @DisplayName("Partida dentro de área de risco: avisa primeiro, e o setor não entra no desvio")
    void partidaEmAreaDeRisco() {
        elevacoes(4.0, 12.0, p -> 10.0);
        when(sgb.buscarProximos(anyDouble(), anyDouble(), anyInt())).thenReturn(List.of(setor("H9", LAT, LON, 400, true)));
        roteadorEncontra();

        RotaSeguraResponse r = calcular(ModoRota.A_PE);

        assertEquals("ROTA_ENCONTRADA", r.status());
        assertTrue(r.avisos().get(0).contains("O ponto de partida está dentro de uma área de risco"));
        assertTrue(r.avisos().get(0).contains("setor H9"));
        verify(roteador).rota(anyDouble(), anyDouble(), anyDouble(), anyDouble(), any(), argThat(List::isEmpty));
    }

    @Test
    @DisplayName("Sem altura do terreno o caminho sai mesmo assim, avisando que não foi conferido; sem roteador, o destino fica no mapa")
    void indisponibilidades() {
        when(elevacao.elevacoes(anyList())).thenThrow(new IntegracaoExternaException("Open-Meteo", "fora do ar"));
        when(sgb.buscarProximos(anyDouble(), anyDouble(), anyInt())).thenThrow(new IntegracaoExternaException("SGB", "fora do ar"));
        roteadorEncontra();

        RotaSeguraResponse r = calcular(ModoRota.A_PE);

        assertEquals("ROTA_ENCONTRADA", r.status());
        assertNull(r.origem().elevacao());
        assertNull(r.destino().ganhoElevacao());
        assertNull(r.perfil());
        assertTrue(r.destino().descricao().endsWith("em linha reta."), r.destino().descricao());
        assertTrue(r.avisos().get(0).contains("Serviço Geológico do Brasil"));
        assertTrue(r.avisos().stream().anyMatch(a -> a.equals(RotaSeguraService.SEM_PERFIL)));

        RotaSeguraService outro = new RotaSeguraService(elevacao, sgb, roteador, fontes);
        org.mockito.Mockito.reset(elevacao, sgb, roteador);
        elevacoes(10.0, 10.0, p -> 10.0);
        when(sgb.buscarProximos(anyDouble(), anyDouble(), anyInt())).thenReturn(List.of());
        when(roteador.rota(anyDouble(), anyDouble(), anyDouble(), anyDouble(), any(), anyList()))
                .thenThrow(new IntegracaoExternaException(ValhallaClient.FONTE, "fora do ar"));
        RotaSeguraResponse semRoteador = outro.calcular(LAT, LON, DESTINO[0], DESTINO[1], ModoRota.A_PE);
        assertEquals("ROTA_INDISPONIVEL", semRoteador.status());
        assertNotNull(semRoteador.destino(), "o destino continua marcado no mapa");
        assertNull(semRoteador.trajeto());
        assertTrue(semRoteador.mensagem().contains("não conseguiu traçar o caminho"));
        verify(roteador, times(1)).rota(anyDouble(), anyDouble(), anyDouble(), anyDouble(), any(), anyList());
    }

    @Test
    @DisplayName("Destino longe demais, no mesmo lugar ou partida fora do Brasil é recusado")
    void destinoInvalido() {
        double[] longe = Geo.deslocar(LAT, LON, 25_000, 0);
        RegraNegocioException e = assertThrows(RegraNegocioException.class,
                () -> service.calcular(LAT, LON, longe[0], longe[1], ModoRota.CARRO));
        assertTrue(e.getMessage().contains("até 20 km"), e.getMessage());
        assertThrows(RegraNegocioException.class, () -> service.calcular(LAT, LON, LAT + 0.0001, LON, ModoRota.A_PE));
        assertThrows(RegraNegocioException.class, () -> service.calcular(LAT, LON, 40.7, -74.0, ModoRota.A_PE));
        assertThrows(RegraNegocioException.class, () -> service.calcular(40.7, -74.0, 40.71, -74.0, ModoRota.A_PE));
        verify(roteador, never()).rota(anyDouble(), anyDouble(), anyDouble(), anyDouble(), any(), anyList());
    }

    @Test
    @DisplayName("Distâncias e tempos em português")
    void formatos() {
        assertEquals("250 m", RotaSeguraService.formatarDistancia(247));
        assertEquals("1,2 km", RotaSeguraService.formatarDistancia(1234));
        assertEquals("3 km", RotaSeguraService.formatarDistancia(3000));
        assertEquals("1 min", RotaSeguraService.formatarTempo(20));
        assertEquals("1 h 5 min", RotaSeguraService.formatarTempo(3900));
    }

    // ------------------------------------------------------------------ andamento (painel sobre o mapa)

    /** Guarda os avisos de andamento na ordem em que chegam. */
    private static final class Avisos implements ProgressoRota {
        final List<ProgressoRota.Evento> eventos = java.util.Collections.synchronizedList(new ArrayList<>());
        volatile boolean desistiu;

        @Override
        public void informar(Evento evento) {
            eventos.add(evento);
        }

        @Override
        public boolean cancelado() {
            return desistiu;
        }

        List<String> de(String etapa) {
            synchronized (eventos) {
                return eventos.stream().filter(e -> e.etapa().equals(etapa)).map(Evento::estado).toList();
            }
        }

        String ultimoDetalhe(String etapa) {
            synchronized (eventos) {
                return eventos.stream().filter(e -> e.etapa().equals(etapa)).reduce((a, b) -> b).map(Evento::detalhe).orElse(null);
            }
        }

        List<String> detalhesEmAndamento(String etapa) {
            synchronized (eventos) {
                return eventos.stream().filter(e -> e.etapa().equals(etapa) && e.estado().equals("EM_ANDAMENTO"))
                        .map(Evento::detalhe).toList();
            }
        }
    }

    @Test
    @DisplayName("Andamento: cada consulta externa é avisada ao começar e ao terminar, com a fonte")
    void andamentoDasEtapas() {
        elevacoes(8.0, 15.0, p -> 10.0);
        double[] meio = Geo.deslocar(LAT, LON, 1000, 30);
        when(sgb.buscarProximos(anyDouble(), anyDouble(), anyInt())).thenReturn(List.of(setor("H1", meio[0], meio[1], 80, true)));
        roteadorEncontra();
        Avisos avisos = new Avisos();

        RotaSeguraResponse r = service.calcular(LAT, LON, DESTINO[0], DESTINO[1], ModoRota.A_PE, avisos);

        assertEquals("ROTA_ENCONTRADA", r.status());
        // primeiro o plano completo, com todas as etapas pendentes e na ordem da tela
        assertEquals(List.of("ALTURA", "SETORES", "CAMINHO", "PERFIL"),
                avisos.eventos.subList(0, 4).stream().map(ProgressoRota.Evento::etapa).toList());
        assertTrue(avisos.eventos.subList(0, 4).stream().allMatch(e -> e.estado().equals("PENDENTE")));
        for (String etapa : List.of("ALTURA", "SETORES", "CAMINHO", "PERFIL")) {
            assertEquals(List.of("PENDENTE", "EM_ANDAMENTO", "CONCLUIDA"), avisos.de(etapa), etapa);
        }
        assertEquals("Altura da partida e do destino", avisos.eventos.get(0).rotulo());
        assertEquals("Áreas de risco no trajeto", avisos.eventos.get(1).rotulo());
        assertEquals("2 pontos analisados", avisos.ultimoDetalhe("ALTURA"));
        assertEquals("1 área de risco de inundação encontrada", avisos.ultimoDetalhe("SETORES"));
        assertEquals("600 m pelas ruas", avisos.ultimoDetalhe("CAMINHO"));
        assertEquals(List.of("desviando de 1 área de risco"), avisos.detalhesEmAndamento("CAMINHO"));
        ProgressoRota.Evento caminho = avisos.eventos.stream().filter(e -> e.etapa().equals("CAMINHO")).findFirst().orElseThrow();
        assertEquals("Caminho pelas ruas", caminho.rotulo());
        assertTrue(caminho.fonte().contains("Valhalla"));
        assertTrue(avisos.eventos.stream().filter(e -> e.etapa().equals("SETORES")).allMatch(e -> e.fonte().contains("SGB")));

        // a mesma consulta logo depois vem do cache, sem etapas
        Avisos deNovo = new Avisos();
        service.calcular(LAT, LON, DESTINO[0], DESTINO[1], ModoRota.A_PE, deNovo);
        assertTrue(deNovo.eventos.isEmpty());
    }

    @Test
    @DisplayName("Andamento: falhas e a nova tentativa sem desvio aparecem na etapa certa")
    void andamentoComFalhas() {
        elevacoes(10.0, 10.0, p -> null);
        when(sgb.buscarProximos(anyDouble(), anyDouble(), anyInt())).thenThrow(new IntegracaoExternaException("SGB", "fora do ar"));
        roteadorEncontra();
        Avisos avisos = new Avisos();

        RotaSeguraResponse r = service.calcular(LAT, LON, DESTINO[0], DESTINO[1], ModoRota.CARRO, avisos);

        assertEquals("ROTA_ENCONTRADA", r.status());
        assertEquals(List.of("PENDENTE", "EM_ANDAMENTO", "FALHOU"), avisos.de("SETORES"));
        assertTrue(avisos.ultimoDetalhe("SETORES").contains("não vai desviar"));
        assertEquals(List.of("traçando o caminho"), avisos.detalhesEmAndamento("CAMINHO"));
        assertEquals(List.of("PENDENTE", "EM_ANDAMENTO", "FALHOU"), avisos.de("PERFIL"), "perfil sem altura dos pontos");
        assertTrue(r.avisos().contains(RotaSeguraService.SEM_PERFIL));

        // o desvio falha e a segunda tentativa é avisada
        double[] meio = Geo.deslocar(LAT, LON, 1000, 30);
        org.mockito.Mockito.reset(elevacao, sgb, roteador);
        elevacoes(10.0, 10.0, p -> 10.0);
        when(sgb.buscarProximos(anyDouble(), anyDouble(), anyInt())).thenReturn(List.of(setor("H1", meio[0], meio[1], 80, true)));
        when(roteador.rota(anyDouble(), anyDouble(), anyDouble(), anyDouble(), any(), argThat(l -> l != null && !l.isEmpty())))
                .thenThrow(new IntegracaoExternaException(ValhallaClient.FONTE, "sem caminho"));
        when(roteador.rota(anyDouble(), anyDouble(), anyDouble(), anyDouble(), any(), argThat(l -> l != null && l.isEmpty())))
                .thenAnswer(inv -> rotaAte(inv.getArgument(2), inv.getArgument(3)));
        Avisos novaTentativa = new Avisos();

        service.calcular(LAT, LON, DESTINO[0], DESTINO[1], ModoRota.A_PE, novaTentativa);

        assertEquals(List.of("PENDENTE", "EM_ANDAMENTO", "EM_ANDAMENTO", "CONCLUIDA"), novaTentativa.de("CAMINHO"));
        assertEquals(List.of("desviando de 1 área de risco", "nova tentativa, sem o desvio (a anterior não deu certo)"),
                novaTentativa.detalhesEmAndamento("CAMINHO"));
    }

    @Test
    @DisplayName("Andamento: roteador fora do ar marca a etapa do caminho como falha, sem perfil")
    void andamentoSemRoteador() {
        elevacoes(10.0, 10.0, p -> 10.0);
        when(sgb.buscarProximos(anyDouble(), anyDouble(), anyInt())).thenReturn(List.of());
        when(roteador.rota(anyDouble(), anyDouble(), anyDouble(), anyDouble(), any(), anyList()))
                .thenThrow(new IntegracaoExternaException(ValhallaClient.FONTE, "fora do ar"));
        Avisos avisos = new Avisos();

        RotaSeguraResponse r = service.calcular(LAT, LON, DESTINO[0], DESTINO[1], ModoRota.A_PE, avisos);

        assertEquals("ROTA_INDISPONIVEL", r.status());
        assertEquals(List.of("PENDENTE", "EM_ANDAMENTO", "FALHOU"), avisos.de("CAMINHO"));
        assertEquals(List.of("PENDENTE"), avisos.de("PERFIL"), "sem caminho, não há perfil");
    }

    @Test
    @DisplayName("Quem desistiu (cancelou ou fechou a página) não gasta chamadas ao roteador")
    void cancelamento() {
        elevacoes(10.0, 10.0, p -> 10.0);
        when(sgb.buscarProximos(anyDouble(), anyDouble(), anyInt())).thenReturn(List.of());
        Avisos avisos = new Avisos();
        avisos.desistiu = true;

        assertThrows(ProgressoRota.CalculoCanceladoException.class,
                () -> service.calcular(LAT, LON, DESTINO[0], DESTINO[1], ModoRota.A_PE, avisos));
        verify(roteador, never()).rota(anyDouble(), anyDouble(), anyDouble(), anyDouble(), any(), anyList());

        // o cancelamento não fica no cache: quem pedir de novo recebe a rota
        roteadorEncontra();
        assertEquals("ROTA_ENCONTRADA", calcular(ModoRota.A_PE).status());
    }

    @Test
    @DisplayName("Uma falha ao avisar o andamento não interrompe o cálculo")
    void andamentoComErroNaTela() {
        elevacoes(10.0, 10.0, p -> 10.0);
        when(sgb.buscarProximos(anyDouble(), anyDouble(), anyInt())).thenReturn(List.of());
        roteadorEncontra();

        RotaSeguraResponse r = service.calcular(LAT, LON, DESTINO[0], DESTINO[1], ModoRota.A_PE, e -> {
            throw new IllegalStateException("tela fechada");
        });

        assertEquals("ROTA_ENCONTRADA", r.status());
    }
}
