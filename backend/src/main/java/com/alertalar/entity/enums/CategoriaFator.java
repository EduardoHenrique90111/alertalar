package com.alertalar.entity.enums;

/**
 * Separa as dimensões da análise, para não misturar risco histórico,
 * suscetibilidade geográfica e mapeamento oficial de risco.
 */
public enum CategoriaFator {
    /** Áreas de risco mapeadas por órgão oficial (ex.: SGB). */
    RISCO_MAPEADO,
    /** Características do terreno: proximidade de água e elevação relativa. */
    SUSCETIBILIDADE,
    /** Ocorrências registradas no passado. */
    HISTORICO
}
