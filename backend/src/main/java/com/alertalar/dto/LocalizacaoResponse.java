package com.alertalar.dto;

import java.util.List;

/**
 * Resultado da geocodificação para o usuário confirmar a posição no mapa antes da análise.
 * @param suficienteParaAnalise false quando o endereço só foi encontrado no nível de bairro/cidade
 */
public record LocalizacaoResponse(List<Candidato> candidatos, String mensagem) {

    public record Candidato(double latitude, double longitude, String precisao, String descricaoPrecisao,
                            boolean suficienteParaAnalise, String enderecoEncontrado, String fonte) {
    }
}
