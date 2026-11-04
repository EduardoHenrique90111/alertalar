package com.alertalar.entity.enums;

public enum SituacaoFator {
    /** O fator foi calculado com dados reais e entrou na média ponderada. */
    AVALIADO,
    /** A fonte respondeu, mas não há registro; a ausência não é tratada como evidência de baixo risco. */
    SEM_REGISTRO,
    /** A fonte não estava disponível ou não cobre a região. */
    INDISPONIVEL
}
