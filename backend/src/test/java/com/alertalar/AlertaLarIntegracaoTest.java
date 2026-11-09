package com.alertalar;

import com.alertalar.support.RespostasSimuladas;
import com.alertalar.support.ServidorHttpFalso;
import com.alertalar.support.ServidorHttpFalso.Resposta;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Teste de ponta a ponta: sobe a aplicação inteira (Spring Boot + PostgreSQL real), com as
 * APIs externas simuladas por um servidor HTTP local, e percorre os fluxos principais:
 * consulta de risco, persistência, relato, moderação, importação oficial, situação atual,
 * cadastro/login e proteções de segurança.
 *
 * <p>Executado somente quando ALERTALAR_TEST_DB_URL aponta para um banco PostgreSQL
 * EXCLUSIVO PARA TESTES (os dados dele são apagados). Veja o README.</p>
 */
@EnabledIfEnvironmentVariable(named = "ALERTALAR_TEST_DB_URL", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AlertaLarIntegracaoTest {

    private static final String ADMIN_EMAIL = "admin.teste@alertalar.local";
    private static final String ADMIN_SENHA = "SenhaAdmin2026";

    private final JsonMapper json = JsonMapper.builder().build();
    private ServidorHttpFalso apis;
    private ConfigurableApplicationContext app;
    private String base;
    private boolean historicoOficialMigrado;
    private final AtomicReference<String> respostaSgb = new AtomicReference<>(RespostasSimuladas.sgbSemSetores());
    private final AtomicReference<String> respostaNominatim = new AtomicReference<>(RespostasSimuladas.nominatimNumero());

    @BeforeAll
    void subir() throws Exception {
        String url = System.getenv("ALERTALAR_TEST_DB_URL");
        String usuario = valorOu(System.getenv("ALERTALAR_TEST_DB_USERNAME"), "alertalar");
        String senha = valorOu(System.getenv("ALERTALAR_TEST_DB_PASSWORD"), "");
        limparBanco(url, usuario, senha);

        apis = new ServidorHttpFalso()
                .rota("/viacep/", Resposta.json(RespostasSimuladas.viaCep()))
                .rota("/nominatim/search", r -> Resposta.json(respostaNominatim.get()))
                .rota("/ibge/estados/RS/municipios", Resposta.json(RespostasSimuladas.ibgeMunicipiosRs()))
                .rota("/overpass", Resposta.json(RespostasSimuladas.overpassComRioECorrego()))
                .rota("/elevacao", r -> Resposta.json(RespostasSimuladas.elevacao(r.parametro("latitude").split(",").length)))
                .rota("/previsao", r -> Resposta.json(RespostasSimuladas.previsaoChuva(LocalDateTime.now(ZoneOffset.UTC))))
                .rota("/vazao", r -> Resposta.json(RespostasSimuladas.vazao(LocalDate.now(ZoneOffset.UTC))))
                .rota("/sgb/query", r -> Resposta.json(respostaSgb.get()))
                .rota("/valhalla/route", r -> {
                    JsonNode locais = json.readTree(r.corpo()).path("locations");
                    return Resposta.json(RespostasSimuladas.valhallaRota(locais.get(0).path("lat").asDouble(),
                            locais.get(0).path("lon").asDouble(), locais.get(1).path("lat").asDouble(),
                            locais.get(1).path("lon").asDouble()));
                });

        app = SpringApplication.run(AlertaLarApplication.class,
                "--server.port=0",
                "--spring.datasource.url=" + url,
                "--spring.datasource.username=" + usuario,
                "--spring.datasource.password=" + senha,
                "--alertalar.admin.email=" + ADMIN_EMAIL,
                "--alertalar.admin.senha=" + ADMIN_SENHA,
                "--alertalar.integracoes.viacep-url=" + apis.url("/viacep"),
                "--alertalar.integracoes.nominatim-url=" + apis.url("/nominatim"),
                "--alertalar.integracoes.nominatim-intervalo-minimo=10ms",
                "--alertalar.integracoes.ibge-url=" + apis.url("/ibge"),
                "--alertalar.integracoes.overpass-url=" + apis.url("/overpass"),
                "--alertalar.integracoes.elevacao-url=" + apis.url("/elevacao"),
                "--alertalar.integracoes.previsao-url=" + apis.url("/previsao"),
                "--alertalar.integracoes.vazao-url=" + apis.url("/vazao"),
                "--alertalar.integracoes.sgb-setores-risco-url=" + apis.url("/sgb"),
                "--alertalar.integracoes.rotas-url=" + apis.url("/valhalla"),
                "--alertalar.integracoes.rotas-intervalo-minimo=10ms",
                "--alertalar.limites.rotas-por-minuto=30",
                "--logging.level.root=WARN");
        base = "http://127.0.0.1:" + app.getEnvironment().getProperty("local.server.port");
        historicoOficialMigrado = migracaoAplicada(url, usuario, senha, "4");
        // O Flyway carrega a base oficial do Atlas (27 mil registros) num banco novo; os testes
        // abaixo partem de um banco sem ocorrências para conferir contagens exatas (o administrador
        // criado na inicialização é mantido).
        executar(url, usuario, senha, "TRUNCATE consulta_fator, consulta, ocorrencia RESTART IDENTITY CASCADE");
    }

    @AfterAll
    void descer() {
        if (app != null) app.close();
        if (apis != null) apis.close();
    }

    // ------------------------------------------------------------------ testes

    @Test
    @Order(1)
    @DisplayName("Fontes de dados cadastradas pela migration estão disponíveis")
    void fontes() throws Exception {
        Cliente c = new Cliente();
        JsonNode fontes = c.getJson("/api/fontes");
        assertTrue(fontes.size() >= 13);
        assertTrue(textoContem(fontes, "SGB_SETORES_RISCO"));
    }

    @Test
    @Order(2)
    @DisplayName("Segurança: CSRF obrigatório, área administrativa protegida e cabeçalhos de segurança")
    void seguranca() throws Exception {
        Cliente c = new Cliente();
        HttpResponse<String> semCsrf = c.enviar("POST", "/api/consultas", "{}", false);
        assertEquals(403, semCsrf.statusCode());
        HttpResponse<String> admin = c.enviar("GET", "/api/admin/resumo", null, false);
        assertEquals(401, admin.statusCode());
        HttpResponse<String> pagina = c.enviar("GET", "/index.html", null, false);
        assertTrue(pagina.headers().firstValue("Content-Security-Policy").orElse("").contains("default-src 'self'"));
        assertEquals("DENY", pagina.headers().firstValue("X-Frame-Options").orElse(""));
    }

    @Test
    @Order(3)
    @DisplayName("Validação de entrada devolve 400 com erros por campo")
    void validacao() throws Exception {
        Cliente c = new Cliente();
        HttpResponse<String> r = c.enviar("POST", "/api/consultas", "{\"rua\":\"\",\"cidade\":\"Canoas\",\"estado\":\"ZZ\"}", true);
        assertEquals(400, r.statusCode());
        JsonNode erros = json.readTree(r.body()).path("erros");
        assertTrue(erros.has("rua") && erros.has("estado"));
    }

    @Test
    @Order(4)
    @DisplayName("Consulta completa: geocodifica, analisa, grava e permite reabrir pelo id")
    void consultaCompleta() throws Exception {
        respostaSgb.set(RespostasSimuladas.sgbSetorContendoPonto());
        Cliente c = new Cliente();
        HttpResponse<String> r = c.enviar("POST", "/api/consultas",
                "{\"cep\":\"92010-000\",\"rua\":\"Rua de Teste\",\"numero\":\"100\",\"cidade\":\"Canoas\",\"estado\":\"RS\"}", true);
        assertEquals(201, r.statusCode(), r.body());
        JsonNode consulta = json.readTree(r.body());
        assertEquals("ALTO", consulta.path("classificacao").asString());
        assertEquals("NUMERO", consulta.path("endereco").path("precisao").asString());
        assertEquals("4304606", consulta.path("endereco").path("codigoIbge").asString());
        assertEquals(5, consulta.path("fatores").size());
        assertEquals(1, consulta.path("mapa").path("setoresRisco").size());

        JsonNode reaberta = c.getJson("/api/consultas/" + consulta.path("id").asString());
        assertEquals("ALTO", reaberta.path("classificacao").asString());
        assertTrue(reaberta.path("justificativa").asString().contains("SGB"));
        assertTrue(textoContem(reaberta.path("fontes"), "SGB_SETORES_RISCO"));
        respostaSgb.set(RespostasSimuladas.sgbSemSetores());
    }

    @Test
    @Order(5)
    @DisplayName("Endereço localizado só no nível de cidade não é analisado (422)")
    void precisaoInsuficiente() throws Exception {
        respostaNominatim.set(RespostasSimuladas.nominatimApenasCidade());
        Cliente c = new Cliente();
        HttpResponse<String> r = c.enviar("POST", "/api/consultas",
                "{\"rua\":\"Rua Desconhecida\",\"numero\":\"5\",\"cidade\":\"Canoas\",\"estado\":\"RS\"}", true);
        assertEquals(422, r.statusCode());
        assertEquals("PRECISAO_INSUFICIENTE", json.readTree(r.body()).path("codigo").asString());
        respostaNominatim.set(RespostasSimuladas.nominatimNumero());
    }

    @Test
    @Order(6)
    @DisplayName("Relato fica pendente, é confirmado pela moderação e passa a contar na análise")
    void relatoEModeracao() throws Exception {
        Cliente anonimo = new Cliente();
        String relato = String.format(java.util.Locale.ROOT, """
                {"localizacao":"Rua de Teste, 120","municipio":"Canoas","estado":"RS","dataOcorrencia":"2024-05-04",
                 "descricao":"A água cobriu a calçada e entrou na garagem.","tipoEvento":"ALAGAMENTO",
                 "latitude":%.6f,"longitude":%.6f,"nomeRelator":"Morador","contatoRelator":"morador@exemplo.com",
                 "declaracaoVeracidade":true}""", RespostasSimuladas.LAT + 0.0005, RespostasSimuladas.LON);
        HttpResponse<String> criado = anonimo.enviar("POST", "/api/ocorrencias", relato, true);
        assertEquals(201, criado.statusCode(), criado.body());
        assertFalse(criado.body().contains("morador@exemplo.com"), "contato do relator não pode ser exposto");
        assertEquals(0, anonimo.getJson("/api/ocorrencias").path("total").asInt(), "relato pendente não é público");

        Cliente admin = new Cliente();
        admin.login(ADMIN_EMAIL, ADMIN_SENHA);
        JsonNode pendentes = admin.getJson("/api/admin/ocorrencias?status=PENDENTE");
        assertEquals(1, pendentes.path("total").asInt());
        long id = pendentes.path("itens").get(0).path("ocorrencia").path("id").asLong();
        HttpResponse<String> validado = admin.enviar("PATCH", "/api/admin/ocorrencias/" + id + "/validacao",
                "{\"status\":\"CONFIRMADA\",\"observacao\":\"Conferido\"}", true);
        assertEquals(200, validado.statusCode(), validado.body());

        assertEquals(1, anonimo.getJson("/api/ocorrencias").path("total").asInt());
        JsonNode proximas = anonimo.getJson("/api/ocorrencias/proximas?lat=" + RespostasSimuladas.LAT + "&lon=" + RespostasSimuladas.LON + "&raio=500");
        assertEquals(1, proximas.size());

        HttpResponse<String> consulta = anonimo.enviar("POST", "/api/consultas", String.format(java.util.Locale.ROOT,
                "{\"rua\":\"Rua de Teste\",\"numero\":\"100\",\"cidade\":\"Canoas\",\"estado\":\"RS\",\"latitude\":%.6f,\"longitude\":%.6f}",
                RespostasSimuladas.LAT, RespostasSimuladas.LON), true);
        assertEquals(201, consulta.statusCode(), consulta.body());
        JsonNode fator = fator(json.readTree(consulta.body()), "OCORRENCIAS_LOCAIS");
        assertEquals("AVALIADO", fator.path("situacao").asString());
        assertEquals(1, fator.path("pontuacao").asInt());
    }

    @Test
    @Order(7)
    @DisplayName("Importação da planilha oficial alimenta o histórico municipal")
    void importacaoOficial() throws Exception {
        Cliente admin = new Cliente();
        admin.login(ADMIN_EMAIL, ADMIN_SENHA);
        String csv = "Protocolo;UF;Município;Data do Evento;COBRADE\nTESTE-1;RS;Canoas;01/05/2024;1.2.1.0.0\n"
                + "TESTE-2;RS;Canoas;15/06/2023;1.2.3.0.0\n";
        HttpResponse<String> r = admin.multipart("/api/admin/importacoes/registros-oficiais", "registros.csv", csv);
        assertEquals(200, r.statusCode(), r.body());
        assertEquals(2, json.readTree(r.body()).path("importados").asInt());

        Cliente c = new Cliente();
        HttpResponse<String> consulta = c.enviar("POST", "/api/consultas", String.format(java.util.Locale.ROOT,
                "{\"rua\":\"Rua de Teste\",\"numero\":\"100\",\"cidade\":\"Canoas\",\"estado\":\"RS\",\"latitude\":%.6f,\"longitude\":%.6f}",
                RespostasSimuladas.LAT, RespostasSimuladas.LON), true);
        JsonNode corpo = json.readTree(consulta.body());
        JsonNode historico = fator(corpo, "HISTORICO_MUNICIPAL");
        assertEquals("AVALIADO", historico.path("situacao").asString());
        assertEquals(2, corpo.path("historicoMunicipal").path("total").asInt());
    }

    @Test
    @Order(8)
    @DisplayName("Situação atual traz chuva e vazão separadas da classificação")
    void situacaoAtual() throws Exception {
        JsonNode s = new Cliente().getJson("/api/situacao-atual?lat=" + RespostasSimuladas.LAT + "&lon=" + RespostasSimuladas.LON);
        assertTrue(s.path("chuva").path("disponivel").asBoolean());
        assertTrue(s.path("chuva").path("ultimas24hMm").asDouble() > 0);
        assertEquals("SUBINDO", s.path("vazao").path("tendencia").asString());
        assertTrue(textoContem(s.path("avisos"), "40199"));
        // 2 mm/h nas últimas horas simuladas = 48 mm em 24 h: perigo potencial (nível 1)
        assertEquals(1, s.path("riscoAgora").path("nivelChuva").asInt(), s.toString());
        assertEquals("ATENCAO", s.path("riscoAgora").path("nivel").asString(), "sem consulta: suscetibilidade moderada");
        assertTrue(s.path("riscoAgora").path("criterio").asString().contains("INMET"));
    }

    @Test
    @Order(9)
    @DisplayName("Cadastro, login automático e histórico de consultas do usuário")
    void cadastroEHistorico() throws Exception {
        Cliente c = new Cliente();
        HttpResponse<String> reg = c.enviar("POST", "/api/auth/registro",
                "{\"nome\":\"Ana Teste\",\"email\":\"ana.teste@exemplo.com\",\"senha\":\"senha1234\"}", true);
        assertEquals(201, reg.statusCode(), reg.body());
        assertFalse(reg.body().contains("senha"), "a senha/hash nunca deve voltar na resposta");
        assertEquals("ana.teste@exemplo.com", c.getJson("/api/auth/me").path("email").asString());
        c.enviar("POST", "/api/consultas", String.format(java.util.Locale.ROOT,
                "{\"rua\":\"Rua de Teste\",\"numero\":\"100\",\"cidade\":\"Canoas\",\"estado\":\"RS\",\"latitude\":%.6f,\"longitude\":%.6f}",
                RespostasSimuladas.LAT, RespostasSimuladas.LON), true);
        assertEquals(1, c.getJson("/api/consultas/minhas").size());
        HttpResponse<String> repetido = c.enviar("POST", "/api/auth/registro",
                "{\"nome\":\"Ana\",\"email\":\"ANA.TESTE@exemplo.com\",\"senha\":\"senha1234\"}", true);
        assertEquals(422, repetido.statusCode());
        assertEquals(204, c.enviar("POST", "/api/auth/logout", null, true).statusCode());
        assertEquals(204, c.enviar("GET", "/api/auth/me", null, false).statusCode());
    }

    @Test
    @Order(10)
    @DisplayName("Login com senha errada responde 401 com mensagem genérica")
    void loginInvalido() throws Exception {
        Cliente c = new Cliente();
        HttpResponse<String> r = c.enviar("POST", "/api/auth/login", "{\"email\":\"" + ADMIN_EMAIL + "\",\"senha\":\"errada123\"}", true);
        assertEquals(401, r.statusCode());
        assertTrue(r.body().contains("E-mail ou senha incorretos"));
    }

    @Test
    @Order(11)
    @DisplayName("Migração do histórico oficial (Atlas Digital) foi aplicada na inicialização")
    void historicoOficialNaInicializacao() throws Exception {
        assertTrue(historicoOficialMigrado, "migração V4 (base do Atlas Digital) deveria constar como aplicada");
        JsonNode fontes = new Cliente().getJson("/api/fontes");
        assertTrue(fontes.toString().contains("Atlas Digital de Desastres"), fontes.toString());
    }

    @Test
    @Order(12)
    @DisplayName("Rota segura: destino mais alto, caminho do roteador e validação dos parâmetros")
    void rotaSegura() throws Exception {
        respostaSgb.set(RespostasSimuladas.sgbSemSetores());
        Cliente c = new Cliente();
        JsonNode r = c.getJson("/api/rotas/segura?lat=" + RespostasSimuladas.LAT + "&lon=" + RespostasSimuladas.LON + "&modo=A_PE");
        assertEquals("ROTA_ENCONTRADA", r.path("status").asString(), r.toString());
        assertEquals(5, r.path("destino").path("ganhoElevacao").asInt());
        assertEquals(260, r.path("trajeto").path("distanciaMetros").asInt());
        assertEquals(3, r.path("trajeto").path("coordenadas").size());
        assertEquals("Vire à esquerda na Rua do Morro.", r.path("trajeto").path("instrucoes").get(1).path("texto").asString());
        assertTrue(textoContem(r.path("avisos"), "193"));
        assertEquals(1, apis.contar("/valhalla/route"));

        assertFalse(r.path("destino").path("escolhido").asBoolean());

        JsonNode escolhido = c.getJson("/api/rotas/segura?lat=" + RespostasSimuladas.LAT + "&lon=" + RespostasSimuladas.LON
                + "&modo=CARRO&destLat=-29.9100&destLon=-51.1700");
        assertEquals("ROTA_ENCONTRADA", escolhido.path("status").asString(), escolhido.toString());
        assertTrue(escolhido.path("destino").path("escolhido").asBoolean());
        assertEquals(-29.91, escolhido.path("destino").path("latitude").asDouble(), 1e-9);
        assertEquals(400, c.enviar("GET", "/api/rotas/segura?lat=-29.92&lon=-51.18&destLat=-29.91", null, false).statusCode(),
                "destino incompleto");

        assertEquals(400, c.enviar("GET", "/api/rotas/segura?lat=40.7&lon=-74.0", null, false).statusCode(), "fora do Brasil");
        assertEquals(400, c.enviar("GET", "/api/rotas/segura?lat=-29.9&lon=-51.1&modo=AVIAO", null, false).statusCode());
        assertEquals(400, c.enviar("GET", "/api/rotas/segura?lat=-29.9", null, false).statusCode());
    }

    @Test
    @Order(13)
    @DisplayName("Rota segura com andamento: etapas em tempo real, resultado e erros no formato da API")
    void rotaSeguraComAndamento() throws Exception {
        respostaSgb.set(RespostasSimuladas.sgbSemSetores());
        HttpClient http = HttpClient.newHttpClient();
        String caminho = "/api/rotas/segura/progresso?lat=" + RespostasSimuladas.LAT + "&lon=" + RespostasSimuladas.LON + "&modo=CARRO";
        HttpResponse<String> r = http.send(pedidoEventos(caminho), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertEquals(200, r.statusCode(), r.body());
        assertTrue(r.headers().firstValue("Content-Type").orElse("").startsWith("text/event-stream"));
        List<String[]> eventos = lerEventos(r.body());
        assertEquals("etapa", eventos.get(0)[0]);
        JsonNode primeira = json.readTree(eventos.get(0)[1]);
        assertEquals("ALTURA", primeira.path("etapa").asString());
        assertEquals("PENDENTE", primeira.path("estado").asString());
        assertTrue(primeira.path("fonte").asString().contains("Copernicus"));
        for (String etapa : List.of("ALTURA", "SETORES", "CAMINHO", "PERFIL")) {
            assertTrue(eventos.stream().anyMatch(e -> e[1].contains("\"etapa\":\"" + etapa + "\"") && e[1].contains("\"CONCLUIDA\"")),
                    etapa + " concluída: " + r.body());
        }
        String[] ultimo = eventos.get(eventos.size() - 1);
        assertEquals("resultado", ultimo[0]);
        JsonNode rota = json.readTree(ultimo[1]);
        assertEquals("ROTA_ENCONTRADA", rota.path("status").asString(), ultimo[1]);
        assertEquals("CARRO", rota.path("modo").asString());

        // Parâmetros inválidos: recusados antes do andamento, com a resposta de erro comum da API
        HttpResponse<String> fora = http.send(pedidoEventos("/api/rotas/segura/progresso?lat=40.7&lon=-74.0"),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertEquals(400, fora.statusCode());
        assertTrue(fora.headers().firstValue("Content-Type").orElse("").contains("json"), fora.headers().toString());
        HttpResponse<String> longe = http.send(pedidoEventos("/api/rotas/segura/progresso?lat=-29.92&lon=-51.18&destLat=-29.70&destLon=-51.18"),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertEquals(422, longe.statusCode(), longe.body());
        assertTrue(json.readTree(longe.body()).path("detail").asString().contains("até 20 km"), longe.body());
    }

    // ------------------------------------------------------------------ apoio

    private HttpRequest pedidoEventos(String caminho) {
        return HttpRequest.newBuilder(URI.create(base + caminho))
                .header("Accept", "text/event-stream, application/problem+json").GET().build();
    }

    /** Eventos de um corpo text/event-stream: [nome, dados]. */
    private static List<String[]> lerEventos(String corpo) {
        List<String[]> eventos = new ArrayList<>();
        for (String bloco : corpo.split("\\r?\\n\\r?\\n")) {
            String nome = "message";
            StringBuilder dados = new StringBuilder();
            for (String linha : bloco.split("\\r?\\n")) {
                if (linha.startsWith("event:")) nome = linha.substring(6).trim();
                else if (linha.startsWith("data:")) dados.append(dados.isEmpty() ? "" : "\n").append(linha.substring(5).replaceFirst("^ ", ""));
            }
            if (!dados.isEmpty()) eventos.add(new String[]{nome, dados.toString()});
        }
        return eventos;
    }


    private static boolean migracaoAplicada(String url, String usuario, String senha, String versao) {
        try (Connection con = DriverManager.getConnection(url, usuario, senha);
             java.sql.PreparedStatement ps = con.prepareStatement("SELECT success FROM flyway_schema_history WHERE version = ?")) {
            ps.setString(1, versao);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        } catch (Exception e) {
            return false;
        }
    }

    private JsonNode fator(JsonNode consulta, String codigo) {
        for (JsonNode f : consulta.path("fatores").values()) {
            if (codigo.equals(f.path("codigo").asString())) return f;
        }
        throw new AssertionError("fator não encontrado: " + codigo);
    }

    private static boolean textoContem(JsonNode no, String texto) {
        return no.toString().contains(texto);
    }

    private static String valorOu(String v, String padrao) {
        return v == null || v.isBlank() ? padrao : v;
    }

    private static void executar(String url, String usuario, String senha, String sql) throws Exception {
        try (Connection con = DriverManager.getConnection(url, usuario, senha); Statement st = con.createStatement()) {
            st.execute(sql);
        }
    }

    private static void limparBanco(String url, String usuario, String senha) {
        try (Connection con = DriverManager.getConnection(url, usuario, senha); Statement st = con.createStatement()) {
            st.execute("TRUNCATE consulta_fator, consulta, ocorrencia, endereco, usuario RESTART IDENTITY CASCADE");
        } catch (Exception primeiraExecucao) {
            // tabelas ainda não existem: o Flyway as criará na inicialização
        }
    }

    /** Navegador mínimo: guarda cookies de sessão e envia o token CSRF como o front-end faz. */
    private final class Cliente {
        private final CookieManager cookies = new CookieManager();
        private final HttpClient http = HttpClient.newBuilder().cookieHandler(cookies).build();

        JsonNode getJson(String caminho) throws Exception {
            HttpResponse<String> r = enviar("GET", caminho, null, false);
            assertEquals(200, r.statusCode(), caminho + " → " + r.body());
            return json.readTree(r.body());
        }

        void login(String email, String senha) throws Exception {
            HttpResponse<String> r = enviar("POST", "/api/auth/login", "{\"email\":\"" + email + "\",\"senha\":\"" + senha + "\"}", true);
            assertEquals(200, r.statusCode(), r.body());
        }

        HttpResponse<String> enviar(String metodo, String caminho, String corpo, boolean comCsrf) throws Exception {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + caminho)).header("Accept", "application/json");
            if (comCsrf) b.header("X-XSRF-TOKEN", token());
            if (corpo != null) b.header("Content-Type", "application/json");
            b.method(metodo, corpo == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(corpo));
            return http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        }

        HttpResponse<String> multipart(String caminho, String nomeArquivo, String conteudo) throws Exception {
            String limite = "----alertalar" + System.nanoTime();
            String corpo = "--" + limite + "\r\nContent-Disposition: form-data; name=\"arquivo\"; filename=\"" + nomeArquivo
                    + "\"\r\nContent-Type: text/csv\r\n\r\n" + conteudo + "\r\n--" + limite + "--\r\n";
            HttpRequest req = HttpRequest.newBuilder(URI.create(base + caminho))
                    .header("Content-Type", "multipart/form-data; boundary=" + limite)
                    .header("X-XSRF-TOKEN", token())
                    .POST(HttpRequest.BodyPublishers.ofString(corpo, StandardCharsets.UTF_8)).build();
            return http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        }

        private String token() throws Exception {
            String t = lerToken();
            if (t == null) {
                enviar("GET", "/api/auth/csrf", null, false);
                t = lerToken();
            }
            assertNotNull(t, "cookie XSRF-TOKEN não recebido");
            return t;
        }

        private String lerToken() {
            List<HttpCookie> lista = new ArrayList<>(cookies.getCookieStore().getCookies());
            return lista.stream().filter(c -> c.getName().equals("XSRF-TOKEN")).map(HttpCookie::getValue).findFirst().orElse(null);
        }
    }
}
