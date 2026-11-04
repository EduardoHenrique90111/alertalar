package com.alertalar.dto;

import com.alertalar.entity.Ocorrencia;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Ocorrência para exibição pública. Não inclui nome nem contato de quem relatou
 * (proteção de dados pessoais).
 */
public record OcorrenciaResponse(Long id, String localizacao, String municipio, String estado,
                                 LocalDate dataOcorrencia, String descricao, String tipoEvento, String tipoEventoRotulo,
                                 String origem, String origemRotulo, String nivelLocalizacao, Double latitude,
                                 Double longitude, String fonte, Double distanciaMetros, Instant dataRegistro) {

    public static OcorrenciaResponse de(Ocorrencia o) {
        return de(o, null);
    }

    public static OcorrenciaResponse de(Ocorrencia o, Double distanciaMetros) {
        return new OcorrenciaResponse(o.getId(), o.getLocalizacao(), o.getMunicipio(), o.getEstado(),
                o.getDataOcorrencia(), o.getDescricao(), o.getTipoEvento().name(), o.getTipoEvento().getRotulo(),
                o.getOrigem().name(),
                switch (o.getOrigem()) {
                    case OFICIAL -> "Registro oficial";
                    case RELATO_USUARIO -> "Relato de usuário validado";
                },
                o.getNivelLocalizacao().name(), o.getLatitude(), o.getLongitude(),
                o.getFonte() == null ? null : o.getFonte().getNome(),
                distanciaMetros == null ? null : (double) Math.round(distanciaMetros),
                o.getDataRegistro());
    }
}
