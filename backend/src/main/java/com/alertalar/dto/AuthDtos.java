package com.alertalar.dto;

import com.alertalar.entity.Usuario;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** DTOs de cadastro e autenticação. */
public final class AuthDtos {

    private AuthDtos() {
    }

    public record RegistroRequest(
            @NotBlank(message = "Informe seu nome.")
            @Size(max = 120, message = "O nome deve ter no máximo 120 caracteres.")
            String nome,

            @NotBlank(message = "Informe seu e-mail.")
            @Email(message = "E-mail inválido.")
            @Size(max = 254, message = "O e-mail deve ter no máximo 254 caracteres.")
            String email,

            @NotBlank(message = "Informe uma senha.")
            @Size(min = 8, max = 72, message = "A senha deve ter entre 8 e 72 caracteres.")
            @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).+$", message = "A senha deve conter letras e números.")
            String senha) {
    }

    public record LoginRequest(
            @NotBlank(message = "Informe seu e-mail.")
            @Email(message = "E-mail inválido.")
            String email,

            @NotBlank(message = "Informe sua senha.")
            @Size(max = 72, message = "Senha inválida.")
            String senha) {
    }

    /** Dados do usuário devolvidos ao front-end (nunca inclui o hash da senha). */
    public record UsuarioResponse(Long id, String nome, String email, String papel) {
        public static UsuarioResponse de(Usuario u) {
            return new UsuarioResponse(u.getId(), u.getNome(), u.getEmail(), u.getPapel().name());
        }
    }
}
