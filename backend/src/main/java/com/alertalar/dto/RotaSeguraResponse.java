package com.alertalar.dto;

import java.time.Instant;
import java.util.List;

/**
 * Rota sugerida até um terreno mais alto (ou até um destino escolhido pela pessoa), desviando de
 * áreas de risco mapeadas.
 *
 * @param status ROTA_ENCONTRADA, JA_EM_TERRENO_ALTO, SEM_TERRENO_ALTO ou ROTA_INDISPONIVEL
 */
public record RotaSeguraResponse(
        String status,
        String mensagem,
        String modo,
        String modoRotulo,
        Ponto origem,
        Destino destino,
        Trajeto trajeto,
        Perfil perfil,
        List<SetorMapa> setoresRisco,
        List<Ponto> alternativas,
        List<String> avisos,
        List<String> fontes,
        Instant calculadoEm) {

    /** Ponto com elevação do terreno (m), quando conhecida. */
    public record Ponto(double latitude, double longitude, Double elevacao) {
    }

    /**
     * @param ganhoElevacao diferença de altura em relação à partida (negativa se o destino é mais baixo)
     * @param escolhido     true quando o destino foi escolhido pela pessoa (e não o terreno alto automático)
     */
    public record Destino(double latitude, double longitude, Double elevacao, Double ganhoElevacao,
                          double distanciaRetaMetros, String descricao, boolean escolhido) {
    }

    /**
     * @param coordenadas pontos [lat, lon] do caminho
     * @param desvioAreasRiscoAplicado se o cálculo conseguiu evitar os setores de risco do SGB
     */
    public record Trajeto(double distanciaMetros, double duracaoSegundos, List<double[]> coordenadas,
                          List<Instrucao> instrucoes, boolean desvioAreasRiscoAplicado, int setoresEvitados) {
    }

    public record Instrucao(String texto, double distanciaMetros, double duracaoSegundos) {
    }

    /**
     * Perfil de elevação ao longo do caminho.
     * @param amostras pares [distância desde o início (m), elevação (m)]
     * @param pontoMaisBaixo [lat, lon] do trecho mais baixo
     */
    public record Perfil(double elevacaoMinima, double elevacaoMaxima, double distanciaPontoMaisBaixo,
                         double[] pontoMaisBaixo, List<double[]> amostras) {
    }

    /** Setor de risco hidrológico para desenhar no mapa (anéis de [lat, lon]). */
    public record SetorMapa(String numero, String grau, String tipologia, List<List<double[]>> aneis) {
    }
}
