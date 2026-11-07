package com.alertalar.dto;

import java.time.Instant;
import java.util.List;

/**
 * Situação ATUAL no ponto: chuva e vazão modeladas e o risco deste momento, que combina essas
 * medidas com a suscetibilidade do local. Não altera a classificação da consulta e não substitui
 * os alertas oficiais da Defesa Civil.
 */
public record SituacaoAtualResponse(double latitude, double longitude, RiscoAgora riscoAgora, Chuva chuva, Vazao vazao,
                                    List<String> avisos, Instant consultadoEm) {

    /**
     * @param nivel           BAIXO, ATENCAO, ALTO, MUITO_ALTO ou INDISPONIVEL
     * @param nivelChuva      0 a 3 (limites do INMET para chuvas intensas)
     * @param suscetibilidade classificação da consulta usada no cálculo (null sem consulta)
     * @param proximosDias    aviso de chuva forte prevista entre 24 e 72 h (ou null)
     */
    public record RiscoAgora(String nivel, String rotulo, Integer nivelChuva, String nivelChuvaRotulo,
                             String suscetibilidade, String resumo, List<String> motivos, String proximosDias,
                             String criterio) {
    }

    public record Chuva(boolean disponivel, String motivo, Double ultimas24hMm, Double ultimas72hMm,
                        Double maiorIntensidadeHorariaUltimas24hMm, Double proximas24hMm, Double proximas72hMm,
                        Double maiorIntensidadeHorariaProximas24hMm, Double maior24hEntre24e72hMm, String fonte) {

        public static Chuva indisponivel(String motivo, String fonte) {
            return new Chuva(false, motivo, null, null, null, null, null, null, null, fonte);
        }
    }

    public record Vazao(boolean disponivel, String motivo, Double hojeM3s, Double seteDiasAtrasM3s,
                        Double maximaPrevista7DiasM3s, String tendencia, String fonte, String observacao) {
    }
}
