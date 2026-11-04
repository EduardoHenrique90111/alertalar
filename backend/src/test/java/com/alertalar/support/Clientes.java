package com.alertalar.support;

import com.alertalar.integration.http.ClienteHttpExterno;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.net.http.HttpClient;
import java.time.Duration;

/** Fábrica do cliente HTTP real (mesma configuração da aplicação) para os testes de integração. */
public final class Clientes {

    private Clientes() {
    }

    public static ClienteHttpExterno http() {
        return http(Duration.ofSeconds(3));
    }

    public static ClienteHttpExterno http(Duration timeoutLeitura) {
        HttpClient hc = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(2))
                .build();
        JdkClientHttpRequestFactory fabrica = new JdkClientHttpRequestFactory(hc);
        fabrica.setReadTimeout(timeoutLeitura);
        RestClient rc = RestClient.builder()
                .requestFactory(fabrica)
                .defaultHeader("User-Agent", "AlertaLar-Testes/1.0 (teste@alertalar.local)")
                .build();
        return new ClienteHttpExterno(rc, JsonMapper.builder().build());
    }
}
