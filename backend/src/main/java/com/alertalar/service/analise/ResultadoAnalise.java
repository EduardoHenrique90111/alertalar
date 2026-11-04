package com.alertalar.service.analise;

import com.alertalar.entity.enums.ClassificacaoRisco;

import java.util.List;

/**
 * Resultado consolidado da análise.
 * @param indice média ponderada convertida para 0-100 (nula quando dados insuficientes)
 */
public record ResultadoAnalise(ClassificacaoRisco classificacao, Double indice, List<AvaliacaoFator> fatores,
                               String justificativa, String versaoMetodologia) {
}
