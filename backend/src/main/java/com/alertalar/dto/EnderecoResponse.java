package com.alertalar.dto;

import com.alertalar.entity.Endereco;

public record EnderecoResponse(String descricao, String cep, String rua, String numero, String bairro, String cidade,
                               String estado, String codigoIbge, double latitude, double longitude, String precisao,
                               String descricaoPrecisao, String fonteGeocodificacao) {

    public static EnderecoResponse de(Endereco e) {
        return new EnderecoResponse(e.descricaoCompleta(), e.getCep(), e.getRua(), e.getNumero(), e.getBairro(),
                e.getCidade(), e.getEstado(), e.getCodigoIbge(), e.getLatitude(), e.getLongitude(),
                e.getPrecisao().name(), e.getPrecisao().getDescricao(), e.getFonteGeocodificacao());
    }
}
