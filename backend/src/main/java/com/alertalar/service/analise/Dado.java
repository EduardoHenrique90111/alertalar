package com.alertalar.service.analise;

import java.time.Instant;
import java.util.function.Supplier;

/**
 * Valor obtido de uma fonte, ou o motivo pelo qual não pôde ser obtido.
 * Permite que cada fonte falhe de forma independente sem derrubar a análise.
 */
public record Dado<T>(T valor, boolean disponivel, String motivoIndisponibilidade, Instant obtidoEm) {

    public static <T> Dado<T> de(T valor) {
        return new Dado<>(valor, true, null, Instant.now());
    }

    public static <T> Dado<T> indisponivel(String motivo) {
        return new Dado<>(null, false, motivo, Instant.now());
    }

    /** Executa a coleta e converte qualquer falha em "indisponível", com mensagem segura. */
    public static <T> Dado<T> coletar(Supplier<T> coleta) {
        try {
            return de(coleta.get());
        } catch (RuntimeException e) {
            String msg = e.getMessage() == null ? "Fonte indisponível." : e.getMessage();
            return indisponivel(msg);
        }
    }
}
