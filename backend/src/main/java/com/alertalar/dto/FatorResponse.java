package com.alertalar.dto;

import com.alertalar.entity.FatorConsulta;

import java.time.Instant;

public record FatorResponse(String codigo, String nome, String categoria, String situacao, Integer pontuacao,
                            double peso, String valorObservado, String explicacao, String fonteCodigo,
                            String fonteNome, Instant dataReferencia) {

    public static FatorResponse de(FatorConsulta f) {
        return new FatorResponse(f.getCodigo(), f.getNome(), f.getCategoria().name(), f.getSituacao().name(),
                f.getPontuacao(), f.getPeso(), f.getValorObservado(), f.getExplicacao(),
                f.getFonte() == null ? null : f.getFonte().getCodigo(),
                f.getFonte() == null ? null : f.getFonte().getNome(),
                f.getDataReferencia());
    }
}
