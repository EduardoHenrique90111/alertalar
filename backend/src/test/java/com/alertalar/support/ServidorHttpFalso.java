package com.alertalar.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * Servidor HTTP local (JDK) usado nos testes para simular as APIs externas com respostas
 * no mesmo formato das reais. Permite testar a comunicação HTTP de verdade, sem internet.
 */
public class ServidorHttpFalso implements AutoCloseable {

    /** Resposta programada: status HTTP, corpo e atraso opcional (para simular tempo esgotado). */
    public record Resposta(int status, String corpo, long atrasoMs) {
        public static Resposta json(String corpo) {
            return new Resposta(200, corpo, 0);
        }

        public static Resposta erro(int status) {
            return new Resposta(status, "{\"error\":true}", 0);
        }
    }

    /** Requisição recebida, para verificação nos testes. */
    public record Requisicao(String metodo, String caminho, String query, String corpo, Map<String, List<String>> cabecalhos) {
        public String cabecalho(String nome) {
            return cabecalhos.entrySet().stream()
                    .filter(e -> e.getKey().equalsIgnoreCase(nome))
                    .map(e -> e.getValue().get(0)).findFirst().orElse(null);
        }

        public String parametro(String nome) {
            String fonte = metodo.equals("POST") && (query == null || query.isEmpty()) ? corpo : query;
            if (fonte == null) return null;
            for (String par : fonte.split("&")) {
                int i = par.indexOf('=');
                if (i > 0 && URLDecoder.decode(par.substring(0, i), StandardCharsets.UTF_8).equals(nome)) {
                    return URLDecoder.decode(par.substring(i + 1), StandardCharsets.UTF_8);
                }
            }
            return null;
        }
    }

    private final HttpServer servidor;
    private final Map<String, Function<Requisicao, Resposta>> rotas = new ConcurrentHashMap<>();
    private final List<Requisicao> recebidas = new CopyOnWriteArrayList<>();

    public ServidorHttpFalso() throws IOException {
        servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        servidor.createContext("/", this::tratar);
        servidor.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        servidor.start();
    }

    /** Rota por prefixo de caminho (ex.: "/viacep/"). */
    public ServidorHttpFalso rota(String prefixo, Function<Requisicao, Resposta> resposta) {
        rotas.put(prefixo, resposta);
        return this;
    }

    public ServidorHttpFalso rota(String prefixo, Resposta resposta) {
        return rota(prefixo, r -> resposta);
    }

    public String url(String caminho) {
        return "http://127.0.0.1:" + servidor.getAddress().getPort() + caminho;
    }

    public List<Requisicao> recebidas() {
        return recebidas;
    }

    public long contar(String prefixo) {
        return recebidas.stream().filter(r -> r.caminho().startsWith(prefixo)).count();
    }

    private void tratar(HttpExchange troca) throws IOException {
        String caminho = troca.getRequestURI().getPath();
        String corpo = new String(troca.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Requisicao req = new Requisicao(troca.getRequestMethod(), caminho, troca.getRequestURI().getRawQuery(), corpo,
                Map.copyOf(troca.getRequestHeaders()));
        recebidas.add(req);
        Resposta resp = rotas.entrySet().stream()
                .filter(e -> caminho.startsWith(e.getKey()))
                .max((a, b) -> Integer.compare(a.getKey().length(), b.getKey().length()))
                .map(e -> e.getValue().apply(req))
                .orElse(new Resposta(404, "{\"erro\":\"rota nao simulada\"}", 0));
        if (resp.atrasoMs() > 0) {
            try {
                Thread.sleep(resp.atrasoMs());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        byte[] bytes = resp.corpo().getBytes(StandardCharsets.UTF_8);
        troca.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        try {
            troca.sendResponseHeaders(resp.status(), bytes.length);
            try (OutputStream out = troca.getResponseBody()) {
                out.write(bytes);
            }
        } catch (IOException ignorada) {
            // o cliente pode ter desistido (teste de tempo esgotado)
        }
    }

    @Override
    public void close() {
        servidor.stop(0);
    }
}
