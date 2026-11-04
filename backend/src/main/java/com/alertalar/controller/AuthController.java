package com.alertalar.controller;

import com.alertalar.dto.AuthDtos.LoginRequest;
import com.alertalar.dto.AuthDtos.RegistroRequest;
import com.alertalar.dto.AuthDtos.UsuarioResponse;
import com.alertalar.entity.Usuario;
import com.alertalar.service.UsuarioService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Cadastro, login (sessão) e dados do usuário logado. O logout é tratado pelo Spring Security. */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UsuarioService usuarios;
    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository contextRepository;
    private final UsuarioAtual usuarioAtual;
    private final SecurityContextHolderStrategy estrategia = SecurityContextHolder.getContextHolderStrategy();

    public AuthController(UsuarioService usuarios, AuthenticationManager authenticationManager,
                          SecurityContextRepository contextRepository, UsuarioAtual usuarioAtual) {
        this.usuarios = usuarios;
        this.authenticationManager = authenticationManager;
        this.contextRepository = contextRepository;
        this.usuarioAtual = usuarioAtual;
    }

    /** Garante que o cookie XSRF-TOKEN exista antes do primeiro POST do front-end. */
    @GetMapping("/csrf")
    public Map<String, String> csrf(CsrfToken token) {
        return Map.of("headerName", token.getHeaderName(), "parameterName", token.getParameterName());
    }

    @PostMapping("/registro")
    public ResponseEntity<UsuarioResponse> registrar(@Valid @RequestBody RegistroRequest req,
                                                     HttpServletRequest request, HttpServletResponse response) {
        Usuario u = usuarios.registrar(req);
        autenticar(u.getEmail(), req.senha(), request, response);
        return ResponseEntity.status(HttpStatus.CREATED).body(UsuarioResponse.de(u));
    }

    @PostMapping("/login")
    public UsuarioResponse login(@Valid @RequestBody LoginRequest req, HttpServletRequest request,
                                 HttpServletResponse response) {
        autenticar(req.email(), req.senha(), request, response);
        return usuarios.porEmail(req.email()).map(UsuarioResponse::de).orElseThrow();
    }

    @GetMapping("/me")
    public ResponseEntity<UsuarioResponse> me() {
        return usuarioAtual.obter()
                .map(u -> ResponseEntity.ok(UsuarioResponse.de(u)))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    private void autenticar(String email, String senha, HttpServletRequest request, HttpServletResponse response) {
        Authentication auth = authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(email.trim(), senha));
        // Proteção contra fixação de sessão: novo identificador após autenticar
        if (request.getSession(false) != null) {
            request.changeSessionId();
        }
        SecurityContext ctx = estrategia.createEmptyContext();
        ctx.setAuthentication(auth);
        estrategia.setContext(ctx);
        contextRepository.saveContext(ctx, request, response);
    }
}
