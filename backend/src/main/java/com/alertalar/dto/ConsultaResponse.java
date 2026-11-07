package com.alertalar.dto;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Resultado completo de uma consulta de risco.
 * @param mapa geometrias reais (cursos d'água do OSM, setores do SGB) registradas no momento da consulta
 */
public record ConsultaResponse(
        UUID id,
        Instant dataConsulta,
        EnderecoResponse endereco,
        String classificacao,
        String rotulo,
        String resumo,
        Double indice,
        String justificativa,
        String versaoMetodologia,
        List<DimensaoResponse> dimensoes,
        List<FatorResponse> fatores,
        List<OcorrenciaResponse> ocorrenciasProximas,
        HistoricoMunicipalResponse historicoMunicipal,
        JsonNode mapa,
        List<FonteResponse> fontes,
        List<String> avisos) {

    /** Resumo de uma dimensão da análise (risco mapeado, suscetibilidade ou histórico). */
    public record DimensaoResponse(String categoria, String titulo, String descricao, String nivel, String rotuloNivel) {
    }

    public record HistoricoMunicipalResponse(boolean municipioIdentificado, boolean baseImportada, int total,
                                             List<OcorrenciaResponse> registros) {
    }

    /** Item resumido para o histórico de consultas do usuário. */
    public record Resumo(UUID id, Instant dataConsulta, String endereco, String classificacao, String rotulo) {
    }
}
