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

/** Escolha do destino (terreno mais alto, fora de áreas de risco) e montagem da rota segura. */
@ExtendWith(MockitoExtension.class)
class RotaSeguraServiceTest {

    private static final double LAT = -29.92;
    private static final double LON = -51.18;
    private static final int TAMANHO_GRADE = 1 + RotaSeguraService.DISTANCIAS_METROS.length * RotaSeguraService.RUMOS;

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

    /** Elevação de cada ponto da grade de busca conforme a distância à partida; o perfil da rota usa outra função. */
    private void elevacoes(Function<Double, Double> porDistanciaGrade, Function<double[], Double> perfil) {
        when(elevacao.elevacoes(anyList())).thenAnswer(inv -> {
            List<double[]> pontos = inv.getArgument(0);
            List<Double> r = new ArrayList<>();
            boolean grade = pontos.size() == TAMANHO_GRADE;
            for (double[] p : pontos) {
                r.add(grade ? porDistanciaGrade.apply(Geo.distanciaMetros(LAT, LON, p[0], p[1])) : perfil.apply(p));
            }
            return r;
        });
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

    // ------------------------------------------------------------------ testes

    @Test
    @DisplayName("Grade de busca: partida + 7 anéis de 12 pontos, de 250 m a 3 km")
    void grade() {
        List<double[]> g = RotaSeguraService.gradeDeBusca(LAT, LON);
        assertEquals(85, g.size());
        assertEquals(LAT, g.get(0)[0]);
        assertEquals(250, Geo.distanciaMetros(LAT, LON, g.get(1)[0], g.get(1)[1]), 2);
        assertEquals(3000, Geo.distanciaMetros(LAT, LON, g.get(84)[0], g.get(84)[1]), 5);
    }

    @Test
    @DisplayName("Destino: prefere 10 m+ mais alto e o mais perto; descarta pontos dentro de área de risco")
    void escolhaDoDestino() {
        List<double[]> grade = List.of(new double[]{LAT, LON},
                Geo.deslocar(LAT, LON, 250, 0),   // +6 m, perto
                Geo.deslocar(LAT, LON, 500, 90),  // +12 m, dentro de setor
                Geo.deslocar(LAT, LON, 800, 180), // +15 m
                Geo.deslocar(LAT, LON, 300, 270), // +2 m: insuficiente
                Geo.deslocar(LAT, LON, 1200, 0)); // +20 m, mais longe
        double[] emRisco = grade.get(2);
        List<RotaSeguraService.Candidato> c = RotaSeguraService.candidatos(grade, List.of(4.0, 10.0, 16.0, 19.0, 6.0, 24.0),
                4.0, List.of(setor("S1", emRisco[0], emRisco[1], 80, true)), LAT, LON);
        assertEquals(3, c.size());
        assertEquals(15, c.get(0).ganho(), 1e-9, "o 10 m+ mais próximo vem primeiro");
        assertEquals(20, c.get(1).ganho(), 1e-9);
        assertEquals(6, c.get(2).ganho(), 1e-9, "5 m+ só como reserva");
    }

    @Test
    @DisplayName("Rota encontrada: destino mais alto, caminho, perfil, fontes e avisos padrão")
    void rotaEncontrada() {
        elevacoes(d -> d < 1 ? 4.0 : (d > 400 && d < 600 ? 20.0 : 4.0), p -> 4.0 + Geo.distanciaMetros(LAT, LON, p[0], p[1]) / 50);
        when(sgb.buscarProximos(anyDouble(), anyDouble(), anyInt())).thenReturn(List.of());
        when(roteador.rota(anyDouble(), anyDouble(), anyDouble(), anyDouble(), eq(ModoRota.A_PE), anyList()))
                .thenAnswer(inv -> rotaAte(inv.getArgument(2), inv.getArgument(3)));

        RotaSeguraResponse r = service.calcular(LAT, LON, ModoRota.A_PE);

        assertEquals("ROTA_ENCONTRADA", r.status());
        assertEquals(16, r.destino().ganhoElevacao(), 1e-9);
        assertEquals(500, r.destino().distanciaRetaMetros(), 3);
        assertEquals(600, r.trajeto().distanciaMetros());
        assertEquals(2, r.trajeto().instrucoes().size());
        assertTrue(r.trajeto().desvioAreasRiscoAplicado());
        assertTrue(r.mensagem().contains("8 min a pé"), r.mensagem());
        assertNotNull(r.perfil());
        assertEquals(4.0, r.perfil().elevacaoMinima(), 1e-9);
        assertTrue(r.fontes().stream().anyMatch(f -> f.contains("Valhalla")));
        assertTrue(r.fontes().stream().anyMatch(f -> f.contains("Copernicus")));
        assertTrue(r.avisos().stream().anyMatch(a -> a.contains("não uma rota oficial")));
        assertTrue(r.avisos().stream().anyMatch(a -> a.contains("Nunca atravesse ruas alagadas")));
        assertEquals(3, r.alternativas().size());
        verify(fontes).registrarAcesso(eq("VALHALLA_ROTAS"), any());
        verify(roteador, times(1)).rota(anyDouble(), anyDouble(), anyDouble(), anyDouble(), any(), anyList());

        // a mesma consulta logo depois vem do cache
        service.calcular(LAT, LON, ModoRota.A_PE);
        verify(roteador, times(1)).rota(anyDouble(), anyDouble(), anyDouble(), anyDouble(), any(), anyList());
    }

    @Test
    @DisplayName("Áreas de risco entre a partida e o destino vão para o roteador evitar")
    void evitaSetores() {
        elevacoes(d -> d > 400 && d < 600 ? 20.0 : 4.0, p -> 10.0);
        double[] meio = Geo.deslocar(LAT, LON, 250, 0);
        double[] longe = Geo.deslocar(LAT, LON, 3000, 180);
        when(sgb.buscarProximos(anyDouble(), anyDouble(), anyInt())).thenReturn(List.of(
                setor("H1", meio[0], meio[1], 60, true),
                setor("H2", longe[0], longe[1], 60, true),
                setor("D1", meio[0], meio[1] + 0.003, 60, false)));
        when(roteador.rota(anyDouble(), anyDouble(), anyDouble(), anyDouble(), any(), anyList()))
                .thenAnswer(inv -> rotaAte(inv.getArgument(2), inv.getArgument(3)));

        RotaSeguraResponse r = service.calcular(LAT, LON, ModoRota.A_PE);

        assertEquals("ROTA_ENCONTRADA", r.status());
        assertEquals(2, r.setoresRisco().size(), "somente setores hidrológicos aparecem no mapa");
        assertEquals(2, r.trajeto().setoresEvitados());
        verify(roteador).rota(anyDouble(), anyDouble(), anyDouble(), anyDouble(), any(), argThat(l -> l.size() == 2));
    }

    @Test
    @DisplayName("Se o desvio falhar, tenta sem desvio e avisa que o caminho pode passar por áreas de risco")
    void semDesvio() {
        elevacoes(d -> d > 400 && d < 600 ? 20.0 : 4.0, p -> 10.0);
        double[] meio = Geo.deslocar(LAT, LON, 1500, 45);
        when(sgb.buscarProximos(anyDouble(), anyDouble(), anyInt())).thenReturn(List.of(setor("H1", meio[0], meio[1], 60, true)));
        when(roteador.rota(anyDouble(), anyDouble(), anyDouble(), anyDouble(), any(), argThat(l -> l != null && !l.isEmpty())))
                .thenThrow(new IntegracaoExternaException(ValhallaClient.FONTE, "sem caminho"));
        when(roteador.rota(anyDouble(), anyDouble(), anyDouble(), anyDouble(), any(), argThat(l -> l != null && l.isEmpty())))
                .thenAnswer(inv -> rotaAte(inv.getArgument(2), inv.getArgument(3)));

        RotaSeguraResponse r = service.calcular(LAT, LON, ModoRota.CARRO);

        assertEquals("ROTA_ENCONTRADA", r.status());
        assertFalse(r.trajeto().desvioAreasRiscoAplicado());
        assertEquals(0, r.trajeto().setoresEvitados());
        assertTrue(r.avisos().stream().anyMatch(a -> a.contains("desvie das áreas de risco")));
        assertTrue(r.mensagem().contains("de carro"));
    }

    @Test
    @DisplayName("Trecho do caminho mais baixo que a partida gera aviso")
    void trechoBaixo() {
        double[] fundo = {(LAT + Geo.deslocar(LAT, LON, 500, 0)[0]) / 2, LON};
        elevacoes(d -> d < 1 ? 8.0 : (d > 400 && d < 600 ? 20.0 : 8.0),
                p -> Geo.distanciaMetros(fundo[0], fundo[1], p[0], p[1]) < 60 ? 2.0 : 9.0);
        when(sgb.buscarProximos(anyDouble(), anyDouble(), anyInt())).thenReturn(List.of());
        when(roteador.rota(anyDouble(), anyDouble(), anyDouble(), anyDouble(), any(), anyList()))
                .thenAnswer(inv -> new Rota(List.of(new double[]{LAT, LON}, fundo,
                        new double[]{inv.getArgument(2), inv.getArgument(3)}), List.of(), 520, 400));

        RotaSeguraResponse r = service.calcular(LAT, LON, ModoRota.A_PE);

        assertEquals(2.0, r.perfil().elevacaoMinima(), 1e-9);
        assertTrue(r.avisos().stream().anyMatch(a -> a.contains("6 m mais baixo que o ponto de partida")), r.avisos().toString());
    }

    @Test
    @DisplayName("Ponto já entre os mais altos da vizinhança: não sugere deslocamento")
    void jaEmTerrenoAlto() {
        elevacoes(d -> d < 1 ? 30.0 : 29.0, p -> 30.0);
        when(sgb.buscarProximos(anyDouble(), anyDouble(), anyInt())).thenReturn(List.of());

        RotaSeguraResponse r = service.calcular(LAT, LON, ModoRota.A_PE);

        assertEquals("JA_EM_TERRENO_ALTO", r.status());
        assertNull(r.trajeto());
        verify(roteador, never()).rota(anyDouble(), anyDouble(), anyDouble(), anyDouble(), any(), anyList());
    }

    @Test
    @DisplayName("Há terreno um pouco mais alto, mas nada 5 m acima: orienta a buscar andar alto")
    void semTerrenoAlto() {
        elevacoes(d -> d < 1 ? 10.0 : 13.5, p -> 10.0);
        when(sgb.buscarProximos(anyDouble(), anyDouble(), anyInt())).thenReturn(List.of());

        RotaSeguraResponse r = service.calcular(LAT, LON, ModoRota.A_PE);

        assertEquals("SEM_TERRENO_ALTO", r.status());
        assertTrue(r.mensagem().contains("andar mais alto"));
    }

    @Test
    @DisplayName("Partida dentro de área de risco: avisa; terreno alto também dentro dela não serve de destino")
    void partidaEmAreaDeRisco() {
        elevacoes(d -> d < 1 ? 4.0 : (d < 300 ? 20.0 : (d > 700 && d < 900 ? 18.0 : 4.0)), p -> 10.0);
        when(sgb.buscarProximos(anyDouble(), anyDouble(), anyInt())).thenReturn(List.of(setor("H9", LAT, LON, 400, true)));
        when(roteador.rota(anyDouble(), anyDouble(), anyDouble(), anyDouble(), any(), anyList()))
                .thenAnswer(inv -> rotaAte(inv.getArgument(2), inv.getArgument(3)));

        RotaSeguraResponse r = service.calcular(LAT, LON, ModoRota.A_PE);

        assertEquals("ROTA_ENCONTRADA", r.status());
        assertEquals(800, r.destino().distanciaRetaMetros(), 3, "os pontos a 250 m estão dentro do setor");
        assertTrue(r.avisos().get(0).contains("dentro de uma área de risco"));
        assertTrue(r.avisos().get(0).contains("setor H9"));
    }

    @Test
    @DisplayName("Sem altura do terreno não indica destino; roteador fora do ar mostra o ponto alto no mapa")
    void indisponibilidades() {
        when(elevacao.elevacoes(anyList())).thenThrow(new IntegracaoExternaException("Open-Meteo", "fora do ar"));
        when(sgb.buscarProximos(anyDouble(), anyDouble(), anyInt())).thenThrow(new IntegracaoExternaException("SGB", "fora do ar"));
        RotaSeguraResponse r = service.calcular(LAT, LON, ModoRota.A_PE);
        assertEquals("ROTA_INDISPONIVEL", r.status());
        assertNull(r.destino());
        assertTrue(r.avisos().get(0).contains("Serviço Geológico do Brasil"));

        RotaSeguraService outro = new RotaSeguraService(elevacao, sgb, roteador, fontes);
        org.mockito.Mockito.reset(elevacao, sgb);
        elevacoes(d -> d > 400 && d < 600 ? 20.0 : 4.0, p -> 10.0);
        when(sgb.buscarProximos(anyDouble(), anyDouble(), anyInt())).thenReturn(List.of());
        when(roteador.rota(anyDouble(), anyDouble(), anyDouble(), anyDouble(), any(), anyList()))
                .thenThrow(new IntegracaoExternaException(ValhallaClient.FONTE, "fora do ar"));
        RotaSeguraResponse semRoteador = outro.calcular(LAT, LON, ModoRota.A_PE);
        assertEquals("ROTA_INDISPONIVEL", semRoteador.status());
        assertNotNull(semRoteador.destino(), "o destino continua marcado no mapa");
        assertTrue(semRoteador.mensagem().contains("16 m mais alto"));
        verify(roteador, times(RotaSeguraService.MAX_DESTINOS_TESTADOS))
                .rota(anyDouble(), anyDouble(), anyDouble(), anyDouble(), any(), anyList());
    }

    @Test
    @DisplayName("Ponto fora do Brasil é recusado; distâncias e tempos em português")
    void validacaoEFormatos() {
        assertThrows(RegraNegocioException.class, () -> service.calcular(40.7, -74.0, ModoRota.A_PE));
        assertEquals("250 m", RotaSeguraService.formatarDistancia(247));
        assertEquals("1,2 km", RotaSeguraService.formatarDistancia(1234));
        assertEquals("1 min", RotaSeguraService.formatarTempo(20));
        assertEquals("1 h 5 min", RotaSeguraService.formatarTempo(3900));
    }
}
