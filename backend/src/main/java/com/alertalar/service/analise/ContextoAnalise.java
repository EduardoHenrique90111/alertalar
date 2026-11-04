package com.alertalar.service.analise;

import com.alertalar.entity.Ocorrencia;
import com.alertalar.integration.overpass.OverpassClient.CursoDagua;
import com.alertalar.integration.sgb.SgbSetoresRiscoClient.SetorRisco;

import java.util.List;

/**
 * Todos os dados reais coletados para um ponto, entregues aos fatores de risco.
 * Os fatores são funções puras deste contexto, o que facilita testá-los isoladamente.
 */
public record ContextoAnalise(
        double latitude,
        double longitude,
        String uf,
        String codigoIbge,
        Parametros parametros,
        Dado<List<CursoDagua>> cursosDagua,
        Dado<PerfilElevacao> elevacao,
        Dado<List<SetorRisco>> setoresRisco,
        Dado<List<OcorrenciaProxima>> ocorrenciasProximas,
        Dado<HistoricoMunicipal> historicoMunicipal) {

    public record Parametros(int raioCursosAguaMetros, int raioOcorrenciasMetros, int raioSetoresRiscoMetros,
                             int anosHistorico) {
    }

    /** Ocorrência confirmada com a distância (m) até o ponto consultado. */
    public record OcorrenciaProxima(Ocorrencia ocorrencia, double distanciaMetros) {
    }

    /**
     * Registros oficiais municipais.
     * @param baseImportadaParaUf indica se a base oficial já foi importada para a UF; sem isso,
     *                            "zero registros" não pode ser interpretado como ausência de eventos
     */
    public record HistoricoMunicipal(List<Ocorrencia> registros, boolean baseImportadaParaUf) {
    }

    /**
     * Elevações (m) do Copernicus DEM.
     * @param elevacaoPonto          elevação no ponto consultado
     * @param elevacaoAgua           elevação no ponto do curso d'água mais próximo (pode ser nula)
     * @param distanciaAguaMetros    distância até esse ponto do curso d'água
     * @param nomeAgua               descrição do curso d'água usado como referência
     * @param mediaEntorno           média das elevações num anel ao redor do ponto
     * @param raioEntornoMetros      raio do anel
     * @param pontosEntorno          quantos pontos do anel tinham dado
     */
    public record PerfilElevacao(Double elevacaoPonto, Double elevacaoAgua, Double distanciaAguaMetros, String nomeAgua,
                                 Double mediaEntorno, int raioEntornoMetros, int pontosEntorno) {
    }
}
