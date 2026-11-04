package com.alertalar.service;

import com.alertalar.dto.AuthDtos.RegistroRequest;
import com.alertalar.entity.Usuario;
import com.alertalar.entity.enums.PapelUsuario;
import com.alertalar.exception.RegraNegocioException;
import com.alertalar.repository.UsuarioRepository;
import com.alertalar.util.Texto;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Optional;

/** Cadastro de usuários e integração com o Spring Security (senhas com hash BCrypt). */
@Service
public class UsuarioService implements UserDetailsService {

    private final UsuarioRepository repository;
    private final PasswordEncoder passwordEncoder;

    public UsuarioService(UsuarioRepository repository, PasswordEncoder passwordEncoder) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public Usuario registrar(RegistroRequest req) {
        return criar(req.nome(), req.email(), req.senha(), PapelUsuario.USUARIO);
    }

    @Transactional
    public Usuario criar(String nome, String email, String senha, PapelUsuario papel) {
        String emailNormalizado = email.trim().toLowerCase(Locale.ROOT);
        if (repository.existsByEmailIgnoreCase(emailNormalizado)) {
            throw new RegraNegocioException("Já existe uma conta com este e-mail.");
        }
        Usuario u = new Usuario(Texto.limpar(nome), emailNormalizado, passwordEncoder.encode(senha), papel);
        return repository.save(u);
    }

    @Transactional(readOnly = true)
    public Optional<Usuario> porEmail(String email) {
        return email == null ? Optional.empty() : repository.findByEmailIgnoreCase(email.trim());
    }

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        Usuario u = repository.findByEmailIgnoreCase(email == null ? "" : email.trim())
                .orElseThrow(() -> new UsernameNotFoundException("Credenciais inválidas"));
        return User.withUsername(u.getEmail())
                .password(u.getSenhaHash())
                .roles(u.getPapel().name())
                .disabled(!u.isAtivo())
                .build();
    }
}
