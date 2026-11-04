package com.alertalar.dto;

import com.alertalar.entity.Ocorrencia;

import java.time.Instant;

/** Ocorrência para a moderação (inclui dados do relator, visíveis apenas a administradores). */
public record OcorrenciaAdminResponse(OcorrenciaResponse ocorrencia, String statusValidacao, String nomeRelator,
                                      String contatoRelator, String observacaoValidacao, Instant dataValidacao) {

    public static OcorrenciaAdminResponse de(Ocorrencia o) {
        return new OcorrenciaAdminResponse(OcorrenciaResponse.de(o), o.getStatusValidacao().name(),
                o.getNomeRelator(), o.getContatoRelator(), o.getObservacaoValidacao(), o.getDataValidacao());
    }
}
