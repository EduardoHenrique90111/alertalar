package com.alertalar.controller;

import com.alertalar.entity.Usuario;
import com.alertalar.service.UsuarioService;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** Obtém o usuário autenticado na requisição atual (se houver). */
@Component
public class UsuarioAtual {

    private final UsuarioService usuarios;

    public UsuarioAtual(UsuarioService usuarios) {
        this.usuarios = usuarios;
    }

    public Optional<Usuario> obter() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
            return Optional.empty();
        }
        return usuarios.porEmail(auth.getName());
    }

    public Usuario obrigatorio() {
        return obter().orElseThrow(() -> new org.springframework.security.access.AccessDeniedException("Não autenticado"));
    }
}
