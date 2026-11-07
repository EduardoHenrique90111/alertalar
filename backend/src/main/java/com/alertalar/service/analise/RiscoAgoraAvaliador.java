package com.alertalar.service.analise;

import com.alertalar.dto.SituacaoAtualResponse.Chuva;
import com.alertalar.dto.SituacaoAtualResponse.RiscoAgora;
import com.alertalar.dto.SituacaoAtualResponse.Vazao;
import com.alertalar.entity.enums.ClassificacaoRisco;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Risco de AGORA: combina a chuva do momento (observada nas últimas horas e prevista para as
 * próximas 24 h) e a subida de rios com a suscetibilidade do local (classificação da consulta).
 *
 * <p>Nível da chuva (0 a 3), pelos limites que o INMET usa nos avisos de chuvas intensas:</p>
 * <ul>
 *   <li>1, perigo potencial: 20 mm em uma hora, ou 30 mm em 24 h (o INMET diz "até 50 mm/dia"
 *       sem piso; 30 mm é o piso adotado pelo AlertaLar);</li>
 *   <li>2, perigo: 30 mm em uma hora, ou 50 mm em 24 h;</li>
 *   <li>3, grande perigo: mais de 60 mm em uma hora, ou mais de 100 mm em 24 h.</li>
 * </ul>
 * Também conta a chuva acumulada nas últimas 72 h (solo encharcado; critério do AlertaLar):
 * 100 mm leva ao nível 1 e 150 mm ao nível 2.
 *
 * <p>Risco agora = 0 sem chuva relevante; com chuva, nível da chuva − 1 + suscetibilidade
 * (baixa 0, moderada ou desconhecida 1, alta 2), limitado a 3. Rio próximo com previsão de
 * subir 50% ou mais garante pelo menos "atenção".</p>
 */
public final class RiscoAgoraAvaliador {

    public static final String CRITERIO = "Chuva medida pelos limites do INMET para avisos de chuvas intensas "
            + "(20 mm/h ou 30 mm em 24 h; 30 mm/h ou 50 mm em 24 h; acima de 60 mm/h ou 100 mm em 24 h) e pelo "
            + "acumulado de 72 h, combinada com a suscetibilidade do local. Dados estimados por modelos "
            + "meteorológicos (Open-Meteo): não é alerta oficial.";

    static final double SUBIDA_RIO = 1.5;

    private RiscoAgoraAvaliador() {
    }

    /**
     * @param suscetibilidade classificação da consulta; null quando não há consulta (tratada como moderada)
     * @param pertoDeAgua     rio, canal ou córrego próximo segundo a consulta
     */
    public static RiscoAgora avaliar(Chuva chuva, Vazao vazao, ClassificacaoRisco suscetibilidade, boolean pertoDeAgua) {
        String susc = suscetibilidade == null ? null : suscetibilidade.name();
        if (chuva == null || !chuva.disponivel()) {
            return new RiscoAgora("INDISPONIVEL", "Indisponível", null, null, susc,
                    "Não foi possível obter os dados de chuva agora, então o risco deste momento não foi calculado. "
                            + "Acompanhe os alertas da Defesa Civil (SMS 40199).",
                    List.of(), null, CRITERIO);
        }
        List<String> motivos = new ArrayList<>();
        int nivelChuva = nivelChuva(chuva, motivos);

        int s = switch (suscetibilidade == null ? ClassificacaoRisco.MODERADO : suscetibilidade) {
            case BAIXO -> 0;
            case ALTO -> 2;
            case MODERADO, DADOS_INSUFICIENTES -> 1;
        };
        int risco = nivelChuva == 0 ? 0 : Math.min(3, nivelChuva - 1 + s);

        if (pertoDeAgua && vazao != null && vazao.disponivel() && vazao.hojeM3s() != null
                && vazao.maximaPrevista7DiasM3s() != null && vazao.hojeM3s() > 0
                && vazao.maximaPrevista7DiasM3s() >= vazao.hojeM3s() * SUBIDA_RIO) {
            long pct = Math.round((vazao.maximaPrevista7DiasM3s() / vazao.hojeM3s() - 1) * 100);
            motivos.add("O rio mais próximo na grade do modelo deve subir cerca de " + pct
                    + "% nos próximos 7 dias, e o local fica perto de água.");
            risco = Math.max(risco, 1);
        }

        String proximosDias = null;
        if (chuva.maior24hEntre24e72hMm() != null && chuva.maior24hEntre24e72hMm() >= 50) {
            proximosDias = "Previsão de até " + mm(chuva.maior24hEntre24e72hMm()) + " de chuva em 24 horas entre amanhã "
                    + "e depois de amanhã: o risco pode aumentar. Volte a consultar.";
        }
        if (motivos.isEmpty()) {
            motivos.add("Sem chuva forte nas últimas 24 horas nem prevista para as próximas 24 horas ("
                    + mm(chuva.ultimas24hMm()) + " nas últimas 24 h; " + mm(chuva.proximas24hMm()) + " previstos).");
        }
        String[] nomes = {"BAIXO", "ATENCAO", "ALTO", "MUITO_ALTO"};
        String[] rotulos = {"Baixo agora", "Atenção", "Alto agora", "Muito alto agora"};
        return new RiscoAgora(nomes[risco], rotulos[risco], nivelChuva, ROTULO_CHUVA[nivelChuva], susc,
                resumo(risco, nivelChuva, s, suscetibilidade != null), List.copyOf(motivos), proximosDias, CRITERIO);
    }

    static final String[] ROTULO_CHUVA = {"Sem chuva forte", "Chuva com perigo potencial", "Chuva forte",
            "Chuva muito forte"};

    /** Nível da chuva (0 a 3) e os motivos, em português. */
    static int nivelChuva(Chuva c, List<String> motivos) {
        int nivel = 0;
        double horaPassada = valor(c.maiorIntensidadeHorariaUltimas24hMm());
        double horaFutura = valor(c.maiorIntensidadeHorariaProximas24hMm());
        double hora = Math.max(horaPassada, horaFutura);
        int porHora = hora > 60 ? 3 : hora >= 30 ? 2 : hora >= 20 ? 1 : 0;
        if (porHora > 0) {
            motivos.add("Chuva de até " + mm(hora) + " em uma hora "
                    + (horaFutura >= horaPassada ? "prevista para as próximas 24 horas." : "nas últimas 24 horas."));
            nivel = porHora;
        }
        double passado = valor(c.ultimas24hMm());
        double futuro = valor(c.proximas24hMm());
        int porDia = Math.max(nivelDia(passado), nivelDia(futuro));
        if (porDia > 0) {
            if (nivelDia(passado) > 0) motivos.add(mm(passado) + " de chuva nas últimas 24 horas.");
            if (nivelDia(futuro) > 0) motivos.add(mm(futuro) + " de chuva previstos para as próximas 24 horas.");
            nivel = Math.max(nivel, porDia);
        }
        double acumulado = valor(c.ultimas72hMm());
        int por72h = acumulado >= 150 ? 2 : acumulado >= 100 ? 1 : 0;
        if (por72h > 0) {
            motivos.add(mm(acumulado) + " de chuva nas últimas 72 horas: o solo encharcado escoa menos água.");
            nivel = Math.max(nivel, por72h);
        }
        return nivel;
    }

    private static int nivelDia(double mm) {
        return mm > 100 ? 3 : mm >= 50 ? 2 : mm >= 30 ? 1 : 0;
    }

    private static final String[] CHUVA_FRASE = {"Sem chuva forte", "Chuva com perigo potencial", "Chuva forte",
            "Chuva muito forte"};
    private static final String[] SUSC_FRASE = {"baixa", "moderada", "alta"};

    /** Primeira frase descreve chuva e local; a segunda diz o que fazer no nível de risco. */
    private static String resumo(int risco, int nivelChuva, int suscetibilidade, boolean suscetibilidadeConhecida) {
        String local = suscetibilidadeConhecida ? " num local de suscetibilidade " + SUSC_FRASE[suscetibilidade] + "."
                : ".";
        if (nivelChuva == 0) {
            String base = "Sem chuva forte agora nem prevista para as próximas 24 horas.";
            if (risco > 0) {
                return base + " Mas o rio próximo deve subir: acompanhe os alertas da Defesa Civil.";
            }
            return suscetibilidade == 2 && suscetibilidadeConhecida
                    ? base + " Este local tende a ser atingido quando chove forte: acompanhe a previsão e os alertas."
                    : base;
        }
        String orientacao = switch (risco) {
            case 0 -> "Não há indício de alagamento agora, mas acompanhe a previsão.";
            case 1 -> "Acompanhe a previsão e os alertas da Defesa Civil e evite ruas que costumam alagar.";
            case 2 -> "Evite ruas alagadas, deixe documentos e remédios à mão e esteja pronto para ir a um local mais alto.";
            default -> "Se a água começar a subir, vá para um local alto (veja a rota segura) e, em emergência, ligue "
                    + "199 ou 193.";
        };
        return CHUVA_FRASE[nivelChuva] + local + " " + orientacao;
    }

    private static double valor(Double v) {
        return v == null ? 0 : v;
    }

    private static String mm(Double v) {
        double x = valor(v);
        return (x == Math.rint(x) ? String.valueOf((long) x) : String.format(Locale.of("pt", "BR"), "%.1f", x)) + " mm";
    }
}
