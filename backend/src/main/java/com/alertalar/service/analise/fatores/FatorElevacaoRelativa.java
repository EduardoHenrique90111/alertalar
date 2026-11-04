package com.alertalar.service.analise.fatores;

import com.alertalar.entity.enums.CategoriaFator;
import com.alertalar.service.analise.AvaliacaoFator;
import com.alertalar.service.analise.ContextoAnalise;
import com.alertalar.service.analise.ContextoAnalise.PerfilElevacao;
import com.alertalar.service.analise.Dado;
import com.alertalar.service.analise.FatorRisco;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Elevação relativa do terreno (Copernicus DEM GLO-90): desnível em relação ao curso
 * d'água mais próximo e em relação ao entorno imediato (pontos baixos acumulam água).
 */
@Component
@Order(3)
public class FatorElevacaoRelativa implements FatorRisco {

    public static final String CODIGO = "ELEVACAO_RELATIVA";
    /** Acima desta distância, o desnível até a água deixa de ser um bom indicador local. */
    static final double DISTANCIA_MAXIMA_AGUA = 1000;

    @Override public String codigo() { return CODIGO; }
    @Override public String nome() { return "Elevação relativa do terreno"; }
    @Override public CategoriaFator categoria() { return CategoriaFator.SUSCETIBILIDADE; }
    @Override public double peso() { return 2.0; }
    @Override public String codigoFonte() { return "COPERNICUS_DEM"; }

    @Override
    public AvaliacaoFator avaliar(ContextoAnalise ctx) {
        Dado<PerfilElevacao> dado = ctx.elevacao();
        if (!dado.disponivel() || dado.valor() == null || dado.valor().elevacaoPonto() == null) {
            return indisponivel("Não foi possível obter a elevação do terreno nesta consulta"
                    + (dado.motivoIndisponibilidade() == null ? "." : " (" + dado.motivoIndisponibilidade() + ")."));
        }
        PerfilElevacao p = dado.valor();
        Integer pontosAgua = null;
        Integer pontosEntorno = null;
        StringBuilder valor = new StringBuilder("Elevação do ponto: ").append(Math.round(p.elevacaoPonto())).append(" m");
        StringBuilder explicacao = new StringBuilder();

        if (p.elevacaoAgua() != null && p.distanciaAguaMetros() != null && p.distanciaAguaMetros() <= DISTANCIA_MAXIMA_AGUA) {
            double desnivel = p.elevacaoPonto() - p.elevacaoAgua();
            pontosAgua = pontuarDesnivelAgua(desnivel);
            valor.append("; ").append(desnivelTexto(desnivel)).append(" em relação a ").append(p.nomeAgua());
            explicacao.append("O terreno do ponto está ").append(desnivelTexto(desnivel)).append(" em relação a ")
                    .append(p.nomeAgua()).append(" (a ").append(Math.round(p.distanciaAguaMetros())).append(" m). ")
                    .append(pontosAgua >= 2 ? "Pequenos desníveis em relação à água aumentam a chance de inundação em cheias. "
                            : pontosAgua == 1 ? "O desnível oferece alguma proteção, mas cheias excepcionais podem superá-lo. "
                            : "O desnível é grande em relação ao curso d'água. ");
        }
        if (p.mediaEntorno() != null && p.pontosEntorno() >= 6) {
            double diferenca = p.elevacaoPonto() - p.mediaEntorno();
            pontosEntorno = pontuarDepressao(diferenca);
            valor.append("; ").append(desnivelTexto(diferenca)).append(" da média do entorno");
            explicacao.append("Em relação ao entorno (raio de ").append(p.raioEntornoMetros()).append(" m), o ponto está ")
                    .append(desnivelTexto(diferenca)).append(" da média. ")
                    .append(pontosEntorno > 0 ? "Pontos mais baixos que a vizinhança tendem a acumular água da chuva. "
                            : "O ponto não fica em uma depressão local. ");
        }
        if (pontosAgua == null && pontosEntorno == null) {
            return indisponivel("A elevação do ponto foi obtida, mas não havia dados suficientes do entorno nem curso "
                    + "d'água próximo para calcular a elevação relativa.");
        }
        int pontuacao = Math.max(pontosAgua == null ? 0 : pontosAgua, pontosEntorno == null ? 0 : pontosEntorno);
        explicacao.append("Fonte: Copernicus DEM GLO-90 (resolução de 90 m); a precisão vertical é de alguns metros e o "
                + "modelo pode incluir altura de construções e vegetação.");
        return avaliado(pontuacao, valor.toString(), explicacao.toString(), dado.obtidoEm());
    }

    static int pontuarDesnivelAgua(double desnivel) {
        if (desnivel <= 2) return 3;
        if (desnivel <= 5) return 2;
        if (desnivel <= 10) return 1;
        return 0;
    }

    static int pontuarDepressao(double diferenca) {
        if (diferenca <= -5) return 2;
        if (diferenca <= -2) return 1;
        return 0;
    }

    private static String desnivelTexto(double d) {
        long m = Math.round(Math.abs(d));
        if (m == 0) {
            return "praticamente no mesmo nível";
        }
        return m + " m " + (d > 0 ? "acima" : "abaixo");
    }
}
