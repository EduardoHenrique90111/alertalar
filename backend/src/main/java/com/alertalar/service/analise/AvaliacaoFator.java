package com.alertalar.service.analise;

import com.alertalar.entity.enums.CategoriaFator;
import com.alertalar.entity.enums.SituacaoFator;

import java.time.Instant;

/**
 * Resultado da avaliação de um fator.
 * @param pontuacao        0 (sem indício) a 3 (indício forte); nulo se não avaliado
 * @param forcaAlto        evidência oficial suficiente para classificar como alto risco
 * @param minimoModerado   evidência suficiente para impedir a classificação "baixo"
 */
public record AvaliacaoFator(String codigo, String nome, CategoriaFator categoria, SituacaoFator situacao,
                             Integer pontuacao, double peso, String valorObservado, String explicacao,
                             String codigoFonte, Instant dataReferencia, boolean forcaAlto, boolean minimoModerado) {

    public boolean avaliado() {
        return situacao == SituacaoFator.AVALIADO && pontuacao != null;
    }
}
