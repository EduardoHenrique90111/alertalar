package com.alertalar.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Aplica limites por IP às rotas que consomem APIs externas ou recebem formulários.
 * Responde 429 (Too Many Requests) no formato Problem Details.
 */
public class LimiteRequisicoesFilter extends OncePerRequestFilter {

    private final LimitadorRequisicoes limitador;
    private final AlertaLarProperties.Limites limites;

    public LimiteRequisicoesFilter(LimitadorRequisicoes limitador, AlertaLarProperties.Limites limites) {
        this.limitador = limitador;
        this.limites = limites;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Regra regra = regraPara(request.getMethod(), request.getRequestURI());
        if (regra != null && !limitador.permitir(regra.grupo() + "|" + request.getRemoteAddr(), regra.limite(), regra.janela())) {
            response.setStatus(429);
            response.setHeader("Retry-After", String.valueOf(regra.janela().toSeconds()));
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"Muitas requisições\",\"status\":429,"
                    + "\"detail\":\"Você fez muitas solicitações em pouco tempo. Aguarde alguns instantes e tente novamente.\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    Regra regraPara(String metodo, String uri) {
        if (uri == null || !uri.startsWith("/api/")) {
            return null;
        }
        boolean post = HttpMethod.POST.matches(metodo);
        if (post && uri.equals("/api/consultas")) {
            return new Regra("consulta", limites.consultasPorMinuto(), Duration.ofMinutes(1));
        }
        if (uri.startsWith("/api/enderecos/")) {
            return new Regra("endereco", limites.consultasPorMinuto() * 2, Duration.ofMinutes(1));
        }
        if (post && uri.equals("/api/ocorrencias")) {
            return new Regra("relato", limites.relatosPorHora(), Duration.ofHours(1));
        }
        if (post && (uri.equals("/api/auth/login") || uri.equals("/api/auth/registro"))) {
            return new Regra("auth", limites.autenticacaoPorMinuto(), Duration.ofMinutes(1));
        }
        if (uri.startsWith("/api/situacao-atual")) {
            return new Regra("situacao", 30, Duration.ofMinutes(1));
        }
        return null;
    }

    record Regra(String grupo, int limite, Duration janela) {
    }
}
