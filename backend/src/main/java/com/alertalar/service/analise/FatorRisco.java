package com.alertalar.service.analise;

import com.alertalar.entity.enums.CategoriaFator;
import com.alertalar.entity.enums.SituacaoFator;

import java.time.Instant;

/**
 * Um fator independente da análise de risco. Cada implementação lê apenas os dados de
 * que precisa no {@link ContextoAnalise} e devolve uma {@link AvaliacaoFator} explicada.
 */
public interface FatorRisco {

    String codigo();

    String nome();

    CategoriaFator categoria();

    /** Peso do fator na média ponderada. */
    double peso();

    /** Código da {@code FonteDados} que sustenta o fator. */
    String codigoFonte();

    AvaliacaoFator avaliar(ContextoAnalise contexto);

    // ---- atalhos para as implementações ----

    default AvaliacaoFator avaliado(int pontuacao, String valor, String explicacao, Instant data) {
        return new AvaliacaoFator(codigo(), nome(), categoria(), SituacaoFator.AVALIADO, pontuacao, peso(), valor,
                explicacao, codigoFonte(), data, false, false);
    }

    default AvaliacaoFator semRegistro(String valor, String explicacao, Instant data) {
        return new AvaliacaoFator(codigo(), nome(), categoria(), SituacaoFator.SEM_REGISTRO, null, peso(), valor,
                explicacao, codigoFonte(), data, false, false);
    }

    default AvaliacaoFator indisponivel(String explicacao) {
        return new AvaliacaoFator(codigo(), nome(), categoria(), SituacaoFator.INDISPONIVEL, null, peso(), null,
                explicacao, codigoFonte(), null, false, false);
    }
}
