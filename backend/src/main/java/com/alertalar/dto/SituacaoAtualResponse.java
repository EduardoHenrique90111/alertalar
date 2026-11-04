package com.alertalar.dto;

import java.time.Instant;
import java.util.List;

/**
 * Situação ATUAL no ponto (chuva e vazão modeladas). É informação de contexto: não altera
 * a classificação de risco e não substitui alertas oficiais da Defesa Civil.
 */
public record SituacaoAtualResponse(double latitude, double longitude, Chuva chuva, Vazao vazao, List<String> avisos,
                                    Instant consultadoEm) {

    public record Chuva(boolean disponivel, String motivo, Double ultimas24hMm, Double proximas24hMm,
                        Double proximas72hMm, Double maiorIntensidadeHorariaProximas24hMm, String fonte) {
    }

    public record Vazao(boolean disponivel, String motivo, Double hojeM3s, Double seteDiasAtrasM3s,
                        Double maximaPrevista7DiasM3s, String tendencia, String fonte, String observacao) {
    }
}
