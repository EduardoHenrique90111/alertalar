package com.alertalar.config;

import com.alertalar.exception.EnderecoNaoEncontradoException;
import com.alertalar.exception.GlobalExceptionHandler;
import com.alertalar.exception.IntegracaoExternaException;
import com.alertalar.exception.PrecisaoInsuficienteException;
import com.alertalar.exception.RegraNegocioException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Limite de requisições, hash de senhas e tratamento de erros sem vazamento de detalhes internos. */
class SegurancaETratamentoDeErrosTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("Limitador bloqueia após o limite e libera quando a janela passa")
    void limitador() {
        Clock[] relogio = {Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC)};
        LimitadorRequisicoes limitador = new LimitadorRequisicoes(new Clock() {
            @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return relogio[0].instant(); }
        });
        assertTrue(limitador.permitir("ip", 2, Duration.ofMinutes(1)));
        assertTrue(limitador.permitir("ip", 2, Duration.ofMinutes(1)));
        assertFalse(limitador.permitir("ip", 2, Duration.ofMinutes(1)));
        assertTrue(limitador.permitir("outro-ip", 2, Duration.ofMinutes(1)));
        relogio[0] = Clock.offset(relogio[0], Duration.ofSeconds(61));
        assertTrue(limitador.permitir("ip", 2, Duration.ofMinutes(1)));
    }

    @Test
    @DisplayName("Regras de limite se aplicam às rotas sensíveis")
    void regrasDeLimite() {
        LimiteRequisicoesFilter filtro = new LimiteRequisicoesFilter(new LimitadorRequisicoes(),
                new AlertaLarProperties.Limites(15, 10, 10));
        assertEquals("consulta", filtro.regraPara("POST", "/api/consultas").grupo());
        assertEquals("relato", filtro.regraPara("POST", "/api/ocorrencias").grupo());
        assertEquals("auth", filtro.regraPara("POST", "/api/auth/login").grupo());
        assertNull(filtro.regraPara("GET", "/api/ocorrencias"));
        assertNull(filtro.regraPara("GET", "/index.html"));
    }

    @Test
    @DisplayName("Senhas são guardadas com BCrypt (hash diferente a cada vez, verificável)")
    void bcrypt() {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
        String h1 = encoder.encode("senhaSegura1");
        String h2 = encoder.encode("senhaSegura1");
        assertNotEquals(h1, h2);
        assertTrue(h1.startsWith("$2"));
        assertTrue(encoder.matches("senhaSegura1", h1));
        assertFalse(encoder.matches("outra", h1));
    }

    @Test
    @DisplayName("Erros de negócio viram respostas HTTP adequadas")
    void statusHttp() {
        assertEquals(404, handler.enderecoNaoEncontrado(new EnderecoNaoEncontradoException("x")).getStatusCode().value());
        assertEquals(422, handler.regraNegocio(new RegraNegocioException("x")).getStatusCode().value());
        assertEquals(503, handler.integracao(new IntegracaoExternaException("ViaCEP", "fora do ar")).getStatusCode().value());
        assertEquals(401, handler.autenticacao(new BadCredentialsException("Bad credentials")).getStatusCode().value());
        ResponseEntity<ProblemDetail> p = handler.precisaoInsuficiente(new PrecisaoInsuficienteException("aproximado", -29.9, -51.1));
        assertEquals(422, p.getStatusCode().value());
        assertEquals("PRECISAO_INSUFICIENTE", p.getBody().getProperties().get("codigo"));
    }

    @Test
    @DisplayName("Erro inesperado não expõe a mensagem interna da exceção")
    void erroInterno() {
        ResponseEntity<ProblemDetail> r = handler.inesperado(new IllegalStateException("SELECT * FROM usuario; senha=123"));
        assertEquals(500, r.getStatusCode().value());
        assertFalse(r.getBody().getDetail().contains("SELECT"));
        assertTrue(r.getBody().getDetail().contains("Código para suporte"));
    }

    @Test
    @DisplayName("Falha de login não informa se o e-mail existe")
    void loginGenerico() {
        assertEquals("E-mail ou senha incorretos.", handler.autenticacao(new BadCredentialsException("Bad credentials")).getBody().getDetail());
    }
}
