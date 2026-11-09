package com.alertalar.integration;

import com.alertalar.exception.IntegracaoExternaException;
import com.alertalar.integration.http.LimitadorIntervalo;
import com.alertalar.integration.ibge.IbgeLocalidadesClient;
import com.alertalar.integration.nominatim.NominatimClient;
import com.alertalar.integration.photon.PhotonClient;
import com.alertalar.integration.nominatim.NominatimClient.ResultadoGeocodificacao;
import com.alertalar.integration.openmeteo.ClimaClient;
import com.alertalar.integration.openmeteo.ElevacaoClient;
import com.alertalar.integration.overpass.OverpassClient;
import com.alertalar.integration.overpass.OverpassClient.CursoDagua;
import com.alertalar.integration.sgb.SgbSetoresRiscoClient;
import com.alertalar.integration.sgb.SgbSetoresRiscoClient.SetorRisco;
import com.alertalar.integration.viacep.ViaCepClient;
import com.alertalar.support.Clientes;
import com.alertalar.support.RespostasSimuladas;
import com.alertalar.support.ServidorHttpFalso;
import com.alertalar.support.ServidorHttpFalso.Resposta;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Comunicação HTTP real com as APIs externas, usando um servidor local que responde no
 * mesmo formato das APIs verdadeiras. Testa parâmetros enviados, leitura das respostas
 * e tratamento de falhas (HTTP 500/429, tempo esgotado, JSON inválido).
 */
class ClientesExternosTest {

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
    @DisplayName("ViaCEP: lê o endereço e trata CEP inexistente")
    void viaCep() {
        servidor.rota("/ws/92010000/", Resposta.json(RespostasSimuladas.viaCep()))
                .rota("/ws/99999999/", Resposta.json(RespostasSimuladas.viaCepInexistente()));
        ViaCepClient cliente = new ViaCepClient(Clientes.http(), servidor.url("/ws"));

        var dados = cliente.buscar("92010-000").orElseThrow();
        assertEquals("Canoas", dados.cidade());
        assertEquals("4304606", dados.codigoIbge());
        assertTrue(cliente.buscar("99999999").isEmpty());
        // segunda consulta do mesmo CEP vem do cache
        cliente.buscar("92010000");
        assertEquals(1, servidor.contar("/ws/92010000/"));
    }

    @Test
    @DisplayName("Photon (reserva do Nominatim): busca só no Brasil, converte GeoJSON e ignora outros países")
    void photon() {
        servidor.rota("/api", Resposta.json(RespostasSimuladas.photon("100")));
        PhotonClient cliente = new PhotonClient(Clientes.http(), servidor.url(""), new LimitadorIntervalo(Duration.ofMillis(10)));

        List<ResultadoGeocodificacao> r = cliente.buscar("Rua de Teste 100, Canoas, Rio Grande do Sul", 10);

        assertEquals(3, r.size(), "o resultado da Argentina fica de fora");
        ResultadoGeocodificacao casa = r.get(0);
        assertEquals(RespostasSimuladas.LAT, casa.latitude(), 1e-9);
        assertEquals(RespostasSimuladas.LON, casa.longitude(), 1e-9);
        assertEquals(30, casa.placeRank());
        assertEquals("100", casa.numero());
        assertEquals("Rua de Teste", casa.rua());
        assertEquals("Centro", casa.bairro());
        assertEquals("Canoas", casa.cidade());
        assertEquals("RS", casa.uf());
        assertEquals("100, Rua de Teste, Centro, Canoas, Rio Grande do Sul, 92010-000, Brasil", casa.nomeExibicao());
        assertEquals(26, r.get(1).placeRank(), "a própria rua");
        assertEquals("Rua de Teste", r.get(1).rua());
        assertTrue(r.get(2).nomeExibicao().startsWith("Canoas Shopping, 6750, Avenida Guilherme Schell"));
        var req = servidor.recebidas().get(0);
        assertEquals("/api", req.caminho());
        assertEquals("Rua de Teste 100, Canoas, Rio Grande do Sul", req.parametro("q"));
        assertEquals("-74.2,-34.0,-28.6,5.5", req.parametro("bbox"));
        assertEquals("10", req.parametro("limit"));
        assertTrue(req.cabecalho("User-Agent").startsWith("AlertaLar"));
    }

    @Test
    @DisplayName("Nominatim: envia busca estruturada, User-Agent e respeita o intervalo mínimo")
    void nominatim() {
        servidor.rota("/search", Resposta.json(RespostasSimuladas.nominatimNumero()));
        NominatimClient cliente = new NominatimClient(Clientes.http(), servidor.url(""), "contato@exemplo.com",
                new LimitadorIntervalo(Duration.ofMillis(300)));

        long inicio = System.nanoTime();
        List<ResultadoGeocodificacao> r = cliente.buscarEstruturado("Rua de Teste", "100", "Canoas", "RS", 5);
        cliente.buscarTexto("Rua de Teste, 100, Canoas", 5);
        long decorrido = Duration.ofNanos(System.nanoTime() - inicio).toMillis();

        assertEquals(1, r.size());
        assertEquals(30, r.get(0).placeRank());
        assertEquals("100", r.get(0).numero());
        assertEquals("RS", r.get(0).uf());
        var req = servidor.recebidas().get(0);
        assertEquals("100 Rua de Teste", req.parametro("street"));
        assertEquals("Rio Grande do Sul", req.parametro("state"));
        assertEquals("br", req.parametro("countrycodes"));
        assertEquals("contato@exemplo.com", req.parametro("email"));
        assertTrue(req.cabecalho("User-Agent").startsWith("AlertaLar"));
        assertTrue(decorrido >= 280, "intervalo mínimo entre chamadas não respeitado: " + decorrido);
    }

    @Test
    @DisplayName("IBGE: resolve código do município ignorando acentos")
    void ibge() {
        servidor.rota("/estados/RS/municipios", Resposta.json(RespostasSimuladas.ibgeMunicipiosRs()));
        IbgeLocalidadesClient cliente = new IbgeLocalidadesClient(Clientes.http(), servidor.url(""));
        assertEquals("4318705", cliente.codigoMunicipio("RS", "sao leopoldo").orElseThrow());
        assertTrue(cliente.codigoMunicipio("RS", "Cidade Inexistente").isEmpty());
    }

    @Test
    @DisplayName("Overpass: envia a consulta por POST e lê rios e córregos com geometria")
    void overpass() {
        servidor.rota("/api/interpreter", Resposta.json(RespostasSimuladas.overpassComRioECorrego()));
        OverpassClient cliente = new OverpassClient(Clientes.http(), servidor.url("/api/interpreter"));

        List<CursoDagua> cursos = cliente.buscarProximos(RespostasSimuladas.LAT, RespostasSimuladas.LON, 1500);

        assertEquals(2, cursos.size());
        CursoDagua rio = cursos.get(0);
        assertTrue(rio.principal());
        assertEquals("rio Rio dos Testes", rio.descricao());
        assertFalse(cursos.get(1).principal());
        String consulta = servidor.recebidas().get(0).parametro("data");
        assertTrue(consulta.contains("around:1500,-29.920000,-51.180000"), consulta);
        assertEquals("POST", servidor.recebidas().get(0).metodo());
    }

    @Test
    @DisplayName("Elevação: envia várias coordenadas numa única requisição")
    void elevacao() {
        servidor.rota("/v1/elevation", Resposta.json(RespostasSimuladas.elevacao(3)));
        ElevacaoClient cliente = new ElevacaoClient(Clientes.http(), servidor.url("/v1/elevation"));
        List<Double> e = cliente.elevacoes(List.of(new double[]{-29.9, -51.1}, new double[]{-29.91, -51.1}, new double[]{-29.92, -51.1}));
        assertEquals(List.of(4.0, 9.0, 9.0), e);
        assertEquals("-29.900000,-29.910000,-29.920000", servidor.recebidas().get(0).parametro("latitude"));
    }

    @Test
    @DisplayName("Elevação: quantidade de valores diferente da pedida é tratada como resposta inválida")
    void elevacaoInconsistente() {
        servidor.rota("/v1/elevation", Resposta.json("{\"elevation\":[1.0]}"));
        ElevacaoClient cliente = new ElevacaoClient(Clientes.http(), servidor.url("/v1/elevation"));
        assertThrows(IntegracaoExternaException.class,
                () -> cliente.elevacoes(List.of(new double[]{-29.9, -51.1}, new double[]{-29.91, -51.1})));
    }

    @Test
    @DisplayName("SGB: lê setor com geometria (lon/lat → lat/lon) e identifica risco hidrológico")
    void sgb() {
        servidor.rota("/MapServer/0/query", Resposta.json(RespostasSimuladas.sgbSetorContendoPonto()));
        SgbSetoresRiscoClient cliente = new SgbSetoresRiscoClient(Clientes.http(), servidor.url("/MapServer/0"));

        List<SetorRisco> setores = cliente.buscarProximos(RespostasSimuladas.LAT, RespostasSimuladas.LON, 300);

        assertEquals(1, setores.size());
        SetorRisco s = setores.get(0);
        assertTrue(s.hidrologico());
        assertEquals("Muito alto", s.grauRisco());
        assertEquals(LocalDate.of(2024, 1, 1), s.dataSetorizacao());
        assertEquals(RespostasSimuladas.LAT - 0.001, s.aneis().get(0).get(0)[0], 1e-9);
        var req = servidor.recebidas().get(0);
        assertEquals("esriGeometryPoint", req.parametro("geometryType"));
        assertEquals("300", req.parametro("distance"));
    }

    @Test
    @DisplayName("SGB: objeto 'error' com HTTP 200 vira falha de integração")
    void sgbErro() {
        servidor.rota("/MapServer/0/query", Resposta.json(RespostasSimuladas.sgbErro()));
        SgbSetoresRiscoClient cliente = new SgbSetoresRiscoClient(Clientes.http(), servidor.url("/MapServer/0"));
        assertThrows(IntegracaoExternaException.class, () -> cliente.buscarProximos(-29.9, -51.1, 300));
    }

    @Test
    @DisplayName("Open-Meteo: lê séries de chuva horária e vazão diária")
    void clima() {
        servidor.rota("/v1/forecast", Resposta.json(RespostasSimuladas.previsaoChuva(LocalDateTime.now(ZoneOffset.UTC))))
                .rota("/v1/flood", Resposta.json(RespostasSimuladas.vazao(LocalDate.now(ZoneOffset.UTC))));
        ClimaClient cliente = new ClimaClient(Clientes.http(), servidor.url("/v1/forecast"), servidor.url("/v1/flood"));

        var chuva = cliente.chuvaHoraria(-29.92, -51.18);
        var vazao = cliente.vazaoDiaria(-29.92, -51.18);

        assertEquals(ZoneOffset.ofHours(-3), chuva.fuso());
        assertEquals(110, chuva.horas().size());
        assertEquals(15, vazao.dias().size());
        assertEquals("precipitation", servidor.recebidas().get(0).parametro("hourly"));
    }

    @Test
    @DisplayName("Falhas HTTP (500, 429), JSON inválido e tempo esgotado viram IntegracaoExternaException")
    void falhas() {
        servidor.rota("/erro500", Resposta.erro(500))
                .rota("/erro429", Resposta.erro(429))
                .rota("/invalido", new Resposta(200, "<html>não é json</html>", 0))
                .rota("/lento", new Resposta(200, "{}", 2500));
        var http = Clientes.http(Duration.ofMillis(500));

        IntegracaoExternaException e500 = assertThrows(IntegracaoExternaException.class,
                () -> http.getJson("Fonte X", java.net.URI.create(servidor.url("/erro500"))));
        assertTrue(e500.getMessage().contains("HTTP 500"));
        IntegracaoExternaException e429 = assertThrows(IntegracaoExternaException.class,
                () -> http.getJson("Fonte X", java.net.URI.create(servidor.url("/erro429"))));
        assertTrue(e429.getMessage().contains("limite de requisições"));
        assertThrows(IntegracaoExternaException.class, () -> http.getJson("Fonte X", java.net.URI.create(servidor.url("/invalido"))));
        IntegracaoExternaException lento = assertThrows(IntegracaoExternaException.class,
                () -> http.getJson("Fonte X", java.net.URI.create(servidor.url("/lento"))));
        assertNotNull(lento.getFonte());
        // nenhuma mensagem expõe detalhes internos (stack trace, classes)
        assertFalse(e500.getMessage().contains("Exception"));
    }
}
