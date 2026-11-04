package com.alertalar.service.analise.fatores;

import com.alertalar.entity.enums.CategoriaFator;
import com.alertalar.entity.enums.SituacaoFator;
import com.alertalar.integration.sgb.SgbSetoresRiscoClient.SetorRisco;
import com.alertalar.service.analise.AvaliacaoFator;
import com.alertalar.service.analise.ContextoAnalise;
import com.alertalar.service.analise.Dado;
import com.alertalar.service.analise.FatorRisco;
import com.alertalar.util.Geo;
import com.alertalar.util.Texto;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Setores de risco hidrológico (inundação, enxurrada, alagamento) mapeados pelo Serviço
 * Geológico do Brasil. É a evidência mais forte disponível: um imóvel dentro de um setor
 * de risco alto ou muito alto é classificado como alto risco.
 */
@Component
@Order(1)
public class FatorSetorRiscoOficial implements FatorRisco {

    public static final String CODIGO = "SETOR_RISCO_SGB";

    @Override public String codigo() { return CODIGO; }
    @Override public String nome() { return "Área de risco mapeada pelo SGB"; }
    @Override public CategoriaFator categoria() { return CategoriaFator.RISCO_MAPEADO; }
    @Override public double peso() { return 3.0; }
    @Override public String codigoFonte() { return "SGB_SETORES_RISCO"; }

    @Override
    public AvaliacaoFator avaliar(ContextoAnalise ctx) {
        Dado<List<SetorRisco>> dado = ctx.setoresRisco();
        int raio = ctx.parametros().raioSetoresRiscoMetros();
        if (!dado.disponivel()) {
            return indisponivel("Não foi possível consultar a base de setores de risco do SGB nesta consulta ("
                    + dado.motivoIndisponibilidade() + ").");
        }
        List<SetorRisco> hidrologicos = dado.valor().stream().filter(SetorRisco::hidrologico).toList();
        if (hidrologicos.isEmpty()) {
            return semRegistro("Nenhum setor hidrológico em " + raio + " m",
                    "Nenhum setor de risco hidrológico (inundação, enxurrada ou alagamento) mapeado pelo SGB num raio de "
                            + raio + " m. O SGB mapeia setores apenas em municípios selecionados, por isso a ausência "
                            + "de setor não indica ausência de risco.", dado.obtidoEm());
        }

        SetorRisco melhor = null;
        boolean dentro = false;
        double menorDistancia = Double.MAX_VALUE;
        for (SetorRisco s : hidrologicos) {
            for (List<double[]> anel : s.aneis()) {
                if (Geo.dentroDoPoligono(ctx.latitude(), ctx.longitude(), anel)) {
                    if (!dentro || grauAlto(s.grauRisco())) {
                        melhor = s;
                    }
                    dentro = true;
                    menorDistancia = 0;
                } else if (!dentro) {
                    double[] p = Geo.pontoMaisProximo(ctx.latitude(), ctx.longitude(), anel);
                    if (p != null && p[2] < menorDistancia) {
                        menorDistancia = p[2];
                        melhor = s;
                    }
                }
            }
        }
        if (melhor == null) {
            // setor retornado sem geometria: o próprio serviço indicou interseção com o raio
            melhor = hidrologicos.get(0);
            menorDistancia = raio;
        }

        String identificacao = "setor " + (melhor.numeroSetor() == null ? "s/n" : melhor.numeroSetor())
                + (melhor.grauRisco() == null ? "" : ", grau de risco " + melhor.grauRisco())
                + (melhor.tipologia() == null ? "" : ", " + melhor.tipologia())
                + (melhor.dataSetorizacao() == null ? "" : ", mapeado em " + melhor.dataSetorizacao().getYear());

        if (dentro) {
            boolean alto = grauAlto(melhor.grauRisco());
            String explicacao = "O ponto está DENTRO de um setor de risco hidrológico mapeado pelo Serviço Geológico do "
                    + "Brasil (" + identificacao + ")"
                    + (melhor.local() == null ? "" : ", local: " + melhor.local()) + ". "
                    + (alto ? "Setores de risco alto ou muito alto são áreas onde o órgão oficial identificou "
                    + "possibilidade de danos a moradias."
                    : "Considere as recomendações da Defesa Civil do município.");
            return new AvaliacaoFator(codigo(), nome(), categoria(), SituacaoFator.AVALIADO, alto ? 3 : 2, peso(),
                    "Dentro do " + identificacao, explicacao, codigoFonte(), dado.obtidoEm(), alto, true);
        }
        int pontos = menorDistancia <= 100 ? 2 : 1;
        String explicacao = "Há um setor de risco hidrológico mapeado pelo SGB a cerca de "
                + Math.round(menorDistancia) + " m do ponto (" + identificacao + "). O ponto não está dentro do "
                + "setor, mas a proximidade indica que a vizinhança já foi identificada como área sujeita a eventos.";
        return avaliado(pontos, "Setor a " + Math.round(menorDistancia) + " m (" + identificacao + ")",
                explicacao, dado.obtidoEm());
    }

    static boolean grauAlto(String grau) {
        String g = Texto.normalizar(grau);
        return g.contains("alto"); // "Alto" e "Muito alto" (R3/R4)
    }
}
