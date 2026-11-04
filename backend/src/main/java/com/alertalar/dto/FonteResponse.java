package com.alertalar.dto;

import com.alertalar.entity.FonteDados;

import java.time.Instant;

public record FonteResponse(String codigo, String nome, String tipo, String url, String statusIntegracao,
                            boolean oficial, Instant dataAtualizacao, String descricao, String atribuicao) {

    public static FonteResponse de(FonteDados f) {
        return new FonteResponse(f.getCodigo(), f.getNome(), f.getTipo().name(), f.getUrl(),
                f.getStatusIntegracao().name(), f.isOficial(), f.getDataAtualizacao(), f.getDescricao(),
                f.getAtribuicao());
    }
}
