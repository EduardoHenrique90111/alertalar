package com.alertalar.dto;

import com.alertalar.entity.enums.StatusValidacao;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Decisão da moderação sobre um relato: CONFIRMADA ou REJEITADA. */
public record ValidacaoRequest(
        @NotNull(message = "Informe o novo status (CONFIRMADA ou REJEITADA).")
        StatusValidacao status,

        @Size(max = 500, message = "A observação deve ter no máximo 500 caracteres.")
        String observacao) {
}
