package com.alertalar.integration.valhalla;

import com.alertalar.exception.IntegracaoExternaException;
import com.alertalar.integration.http.LimitadorIntervalo;
import com.alertalar.integration.valhalla.ValhallaClient.Rota;
import com.alertalar.support.Clientes;
import com.alertalar.support.RespostasSimuladas;
import com.alertalar.support.ServidorHttpFalso;
import com.alertalar.support.ServidorHttpFalso.Resposta;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Cliente do Valhalla contra um servidor local que responde no formato da API real. */
class ValhallaClientTest {

    private static final double LAT_O = -29.92;
    private static final double LON_O = -51.18;
    private static final double LAT_D = -29.9155;
    private static final double LON_D = -51.1785;

    private ServidorHttpFalso servidor;

    @BeforeEach
    void iniciar() throws Exception {
        servidor = new ServidorHttpFalso();
    }

    @AfterEach
    void parar() {
        servidor.close();
    }

    @Test
    @DisplayName("Polyline: decodifica o exemplo oficial (precisão 5) e ida e volta na precisão 6")
    void polyline() {
        List<double[]> p = Polyline.decodificar("_p~iF~ps|U_ulLnnqC_mqNvxq`@", 5);
        assertEquals(3, p.size());
        assertEquals(38.5, p.get(0)[0], 1e-9);
        assertEquals(-120.2, p.get(0)[1], 1e-9);
        assertEquals(40.7, p.get(1)[0], 1e-9);
        assertEquals(-120.95, p.get(1)[1], 1e-9);
        assertEquals(43.252, p.get(2)[0], 1e-9);
        assertEquals(-126.453, p.get(2)[1], 1e-9);

        List<double[]> pontos = List.of(new double[]{-29.920001, -51.180002}, new double[]{-29.915503, -51.178504});
        List<double[]> volta = Polyline.decodificar(Polyline.codificar(pontos, 6), 6);
        assertEquals(-29.920001, volta.get(0)[0], 1e-9);
        assertEquals(-51.178504, volta.get(1)[1], 1e-9);
        assertTrue(Polyline.decodificar("", 6).isEmpty());
    }

    @Test
    @DisplayName("Envia modo, idioma e áreas a evitar em [lon, lat]; lê trajeto, instruções e totais")
    void rota() {
        servidor.rota("/route", Resposta.json(RespostasSimuladas.valhallaRota(LAT_O, LON_O, LAT_D, LON_D)));
        ValhallaClient cliente = new ValhallaClient(Clientes.http(), servidor.url(""), "",
                new LimitadorIntervalo(Duration.ofMillis(10)));
        List<double[]> anel = List.of(new double[]{-29.918, -51.181}, new double[]{-29.918, -51.179},
                new double[]{-29.917, -51.179}, new double[]{-29.918, -51.181});

        Rota rota = cliente.rota(LAT_O, LON_O, LAT_D, LON_D, ModoRota.A_PE, List.of(anel));

        assertEquals(3, rota.pontos().size());
        assertEquals(LAT_O, rota.pontos().get(0)[0], 1e-6);
        assertEquals(LON_D, rota.pontos().get(2)[1], 1e-6);
        assertEquals(260, rota.distanciaMetros(), 1e-6);
        assertEquals(210.5, rota.duracaoSegundos(), 1e-6);
        assertEquals(3, rota.instrucoes().size());
        assertEquals("Vire à esquerda na Rua do Morro.", rota.instrucoes().get(1).texto());
        assertEquals(110, rota.instrucoes().get(1).distanciaMetros(), 1e-6);
        assertEquals(1, rota.instrucoes().get(1).indicePonto());

        var req = servidor.recebidas().get(0);
        assertEquals("POST", req.metodo());
        assertTrue(req.cabecalho("Content-Type").startsWith("application/json"));
        JsonNode corpo = JsonMapper.builder().build().readTree(req.corpo());
        assertEquals("pedestrian", corpo.path("costing").asString());
        assertEquals("pt-BR", corpo.path("directions_options").path("language").asString());
        assertEquals(LAT_D, corpo.path("locations").get(1).path("lat").asDouble(), 1e-9);
        JsonNode primeiroVertice = corpo.path("exclude_polygons").get(0).get(0);
        assertEquals(-51.181, primeiroVertice.get(0).asDouble(), 1e-9, "Valhalla espera [lon, lat]");
        assertEquals(-29.918, primeiroVertice.get(1).asDouble(), 1e-9);
        assertTrue(req.query() == null || !req.query().contains("api_key"), "sem chave configurada, nada vai na URL");
    }

    @Test
    @DisplayName("De carro usa o perfil auto; sem áreas a evitar não envia exclude_polygons; chave vai na URL")
    void carroEChave() {
        servidor.rota("/route", Resposta.json(RespostasSimuladas.valhallaRota(LAT_O, LON_O, LAT_D, LON_D)));
        ValhallaClient cliente = new ValhallaClient(Clientes.http(), servidor.url("/"), "chave-teste",
                new LimitadorIntervalo(Duration.ofMillis(10)));
        cliente.rota(LAT_O, LON_O, LAT_D, LON_D, ModoRota.CARRO, List.of());
        var req = servidor.recebidas().get(0);
        JsonNode corpo = JsonMapper.builder().build().readTree(req.corpo());
        assertEquals("auto", corpo.path("costing").asString());
        assertTrue(corpo.path("exclude_polygons").isMissingNode());
        assertEquals("chave-teste", req.parametro("api_key"));
    }

    @Test
    @DisplayName("Sem caminho (HTTP 400) ou fora do ar (HTTP 500) vira falha de integração")
    void falhas() {
        servidor.rota("/sem-caminho/route", new Resposta(400, RespostasSimuladas.valhallaSemCaminho(), 0))
                .rota("/fora/route", Resposta.erro(500))
                .rota("/vazio/route", Resposta.json("{\"trip\":{\"legs\":[]}}"));
        LimitadorIntervalo limitador = new LimitadorIntervalo(Duration.ofMillis(10));
        for (String caminho : List.of("/sem-caminho", "/fora", "/vazio")) {
            ValhallaClient cliente = new ValhallaClient(Clientes.http(), servidor.url(caminho), "", limitador);
            assertThrows(IntegracaoExternaException.class,
                    () -> cliente.rota(LAT_O, LON_O, LAT_D, LON_D, ModoRota.A_PE, List.of()), caminho);
        }
    }
}
