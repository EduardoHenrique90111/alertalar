package com.alertalar.dto;

import java.util.Set;

/** Resumo de uma importação de registros oficiais. */
public record ImportacaoResultado(String arquivo, int linhasLidas, int importados, int duplicados,
                                  int ignoradosNaoHidrologicos, int invalidos, int semCodigoIbge, Set<String> estados) {
}
