package com.alertalar.config;

import com.alertalar.entity.enums.PapelUsuario;
import com.alertalar.service.UsuarioService;
import com.alertalar.util.Texto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Cria o primeiro administrador a partir das variáveis ALERTALAR_ADMIN_EMAIL e
 * ALERTALAR_ADMIN_SENHA. Não existe senha padrão embutida no código.
 */
@Component
public class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final AlertaLarProperties props;
    private final UsuarioService usuarios;

    public AdminBootstrap(AlertaLarProperties props, UsuarioService usuarios) {
        this.props = props;
        this.usuarios = usuarios;
    }

    @Override
    public void run(ApplicationArguments args) {
        AlertaLarProperties.Admin admin = props.admin();
        if (Texto.vazio(admin.email()) || Texto.vazio(admin.senha())) {
            log.info("Nenhum administrador configurado (ALERTALAR_ADMIN_EMAIL / ALERTALAR_ADMIN_SENHA). "
                    + "A moderação de relatos ficará indisponível até que um seja configurado.");
            return;
        }
        if (admin.senha().length() < 10 || !admin.senha().matches(".*[A-Za-z].*") || !admin.senha().matches(".*\\d.*")) {
            log.error("ALERTALAR_ADMIN_SENHA fraca: use ao menos 10 caracteres com letras e números. Administrador não criado.");
            return;
        }
        usuarios.porEmail(admin.email()).ifPresentOrElse(
                u -> {
                    if (u.getPapel() != PapelUsuario.ADMIN) {
                        log.warn("O e-mail de administrador configurado pertence a uma conta comum; ela NÃO foi promovida automaticamente.");
                    }
                },
                () -> {
                    usuarios.criar(admin.nome(), admin.email(), admin.senha(), PapelUsuario.ADMIN);
                    log.info("Administrador inicial criado para {}", admin.email());
                });
    }
}
