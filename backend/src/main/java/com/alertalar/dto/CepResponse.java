package com.alertalar.dto;

/** Dados do CEP para preenchimento automático do formulário (não contém coordenadas). */
public record CepResponse(String cep, String rua, String bairro, String cidade, String estado, String codigoIbge,
                          String fonte) {
}
