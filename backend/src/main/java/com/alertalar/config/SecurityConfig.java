package com.alertalar.config;

import com.alertalar.service.UsuarioService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Segurança da aplicação:
 * <ul>
 *   <li>sessão com cookie HttpOnly + proteção CSRF (token em cookie XSRF-TOKEN, enviado no
 *       cabeçalho X-XSRF-TOKEN pelo front-end);</li>
 *   <li>senhas com BCrypt;</li>
 *   <li>rotas /api/admin/** restritas ao papel ADMIN;</li>
 *   <li>cabeçalhos: Content-Security-Policy, X-Frame-Options, Referrer-Policy, Permissions-Policy;</li>
 *   <li>limite de requisições por IP nas rotas sensíveis.</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /** Somente recursos próprios + blocos do mapa do OpenStreetMap. Sem scripts ou estilos inline. */
    static final String CSP = String.join("; ",
            "default-src 'self'",
            "script-src 'self'",
            "style-src 'self'",
            "img-src 'self' data: https://tile.openstreetmap.org https://*.tile.openstreetmap.org",
            "connect-src 'self'",
            "font-src 'self'",
            "object-src 'none'",
            "base-uri 'self'",
            "form-action 'self'",
            "frame-ancestors 'none'");

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public AuthenticationManager authenticationManager(UsuarioService usuarios, PasswordEncoder encoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(usuarios);
        provider.setPasswordEncoder(encoder);
        return new ProviderManager(provider);
    }

    @Bean
    public SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    public SecurityFilterChain filtros(HttpSecurity http, SecurityContextRepository contextRepository,
                                       AlertaLarProperties props) throws Exception {
        http
                .csrf(csrf -> csrf.spa())
                .cors(Customizer.withDefaults())
                .securityContext(sc -> sc.securityContextRepository(contextRepository))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/consultas/minhas").authenticated()
                        .requestMatchers("/api/**").permitAll()
                        .requestMatchers("/actuator/health").permitAll()
                        .anyRequest().permitAll())
                .httpBasic(b -> b.disable())
                .formLogin(f -> f.disable())
                .logout(l -> l
                        .logoutUrl("/api/auth/logout")
                        .logoutSuccessHandler((req, res, a) -> res.setStatus(HttpServletResponse.SC_NO_CONTENT))
                        .deleteCookies("JSESSIONID"))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) ->
                                escreverErro(res, 401, "Não autenticado", "Faça login para acessar este recurso."))
                        .accessDeniedHandler((req, res, ex) -> {
                            if (ex instanceof CsrfException) {
                                escreverErro(res, 403, "Token de segurança inválido",
                                        "Sua sessão expirou ou o token de segurança está ausente. Recarregue a página e tente novamente.");
                            } else {
                                escreverErro(res, 403, "Acesso negado", "Você não tem permissão para acessar este recurso.");
                            }
                        }))
                .headers(h -> h
                        .contentSecurityPolicy(c -> c.policyDirectives(CSP))
                        .frameOptions(f -> f.deny())
                        .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                        .permissionsPolicyHeader(p -> p.policy("geolocation=(self), camera=(), microphone=(), payment=()")))
                .addFilterBefore(new LimiteRequisicoesFilter(new LimitadorRequisicoes(), props.limites()),
                        UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /** CORS: por padrão só a própria origem; origens extras via ALERTALAR_CORS_ORIGENS. */
    @Bean
    public CorsConfigurationSource corsConfigurationSource(AlertaLarProperties props) {
        CorsConfiguration cfg = new CorsConfiguration();
        List<String> origens = props.cors().origensPermitidas().stream().filter(o -> !o.isBlank()).toList();
        cfg.setAllowedOrigins(origens);
        cfg.setAllowedMethods(List.of("GET", "POST", "PATCH", "OPTIONS"));
        cfg.setAllowedHeaders(List.of("Content-Type", "X-XSRF-TOKEN"));
        cfg.setAllowCredentials(true);
        cfg.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", cfg);
        return source;
    }

    private static void escreverErro(HttpServletResponse res, int status, String titulo, String detalhe) throws IOException {
        res.setStatus(status);
        res.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        res.setCharacterEncoding(StandardCharsets.UTF_8.name());
        res.getWriter().write("{\"type\":\"about:blank\",\"title\":\"" + titulo + "\",\"status\":" + status
                + ",\"detail\":\"" + detalhe + "\"}");
    }
}
