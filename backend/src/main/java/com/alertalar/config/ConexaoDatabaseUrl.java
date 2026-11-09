package com.alertalar.config;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Aceita a conexão com o banco numa única variável, no formato que o Neon (e outros provedores)
 * mostram para copiar:
 *
 * <pre>DATABASE_URL=postgresql://USUARIO:SENHA@HOST/BANCO?sslmode=require</pre>
 *
 * e a converte para {@code DB_URL} (JDBC), {@code DB_USERNAME} e {@code DB_PASSWORD}. Se essas três
 * variáveis também existirem, elas continuam valendo (esta fonte tem a menor prioridade).
 */
public class ConexaoDatabaseUrl implements EnvironmentPostProcessor, Ordered {

    static final String VARIAVEL = "DATABASE_URL";
    static final String FONTE = "alertalar-database-url";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment ambiente, SpringApplication aplicacao) {
        String valor = ambiente.getProperty(VARIAVEL);
        if (valor == null || valor.isBlank()) {
            return;
        }
        Map<String, Object> propriedades = converter(valor);
        if (!propriedades.isEmpty()) {
            ambiente.getPropertySources().addLast(new MapPropertySource(FONTE, propriedades));
        }
    }

    /** Roda depois da leitura do .env e dos arquivos de configuração. */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    /** postgresql://u:s@host:5432/banco?sslmode=require → DB_URL, DB_USERNAME, DB_PASSWORD (vazio se não reconhecer). */
    static Map<String, Object> converter(String valor) {
        String texto = valor.trim();
        if (texto.startsWith("psql ")) {
            texto = texto.substring(5).trim();
        }
        texto = texto.replaceAll("^['\"]|['\"]$", "");
        if (texto.startsWith("jdbc:postgresql://")) {
            return Map.of("DB_URL", texto);
        }
        URI uri;
        try {
            uri = new URI(texto);
        } catch (URISyntaxException e) {
            return Map.of();
        }
        if (!"postgresql".equals(uri.getScheme()) && !"postgres".equals(uri.getScheme())) {
            return Map.of();
        }
        if (uri.getHost() == null || uri.getRawUserInfo() == null) {
            return Map.of();
        }
        String banco = uri.getPath() == null || uri.getPath().length() <= 1 ? "postgres" : uri.getPath().substring(1);
        String sslmode = "require";
        if (uri.getRawQuery() != null) {
            for (String parte : uri.getRawQuery().split("&")) {
                if (parte.startsWith("sslmode=")) {
                    sslmode = parte.substring("sslmode=".length());
                }
            }
        }
        String porta = uri.getPort() > 0 ? ":" + uri.getPort() : "";
        Map<String, Object> resultado = new LinkedHashMap<>();
        resultado.put("DB_URL", "jdbc:postgresql://" + uri.getHost() + porta + "/" + banco + "?sslmode=" + sslmode);
        String[] credenciais = uri.getRawUserInfo().split(":", 2);
        resultado.put("DB_USERNAME", decodificar(credenciais[0]));
        if (credenciais.length == 2) {
            resultado.put("DB_PASSWORD", decodificar(credenciais[1]));
        }
        return resultado;
    }

    private static String decodificar(String texto) {
        // "+" é literal no usuário/senha de uma URL (não é espaço)
        return URLDecoder.decode(texto.replace("+", "%2B"), StandardCharsets.UTF_8);
    }
}
