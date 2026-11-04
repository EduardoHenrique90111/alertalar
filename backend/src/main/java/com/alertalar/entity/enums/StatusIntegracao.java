package com.alertalar.entity.enums;

/** Situação real da integração com cada fonte (sem simular integrações inexistentes). */
public enum StatusIntegracao {
    /** Integração implementada e consultada pelo sistema. */
    ATIVA,
    /** Dados oficiais carregados por importação de arquivo feita pelo administrador. */
    IMPORTACAO_MANUAL,
    /** Fonte exige cadastro/credencial; integração ainda não implementada. */
    REQUER_CREDENCIAL,
    /** Integração prevista, ainda não implementada. */
    PLANEJADA
}
