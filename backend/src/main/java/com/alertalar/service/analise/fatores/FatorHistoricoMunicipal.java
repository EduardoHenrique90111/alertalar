package com.alertalar.service.analise.fatores;

import com.alertalar.entity.Ocorrencia;
import com.alertalar.entity.enums.CategoriaFator;
import com.alertalar.service.analise.AvaliacaoFator;
import com.alertalar.service.analise.ContextoAnalise;
import com.alertalar.service.analise.ContextoAnalise.HistoricoMunicipal;
import com.alertalar.service.analise.Dado;
import com.alertalar.service.analise.FatorRisco;
import com.alertalar.util.Texto;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Registros oficiais de desastres hidrológicos no MUNICÍPIO (S2iD/Atlas Digital).
 * Como não indicam a rua afetada, têm peso baixo e pontuação máxima 2.
 */
@Component
@Order(5)
public class FatorHistoricoMunicipal implements FatorRisco {

    public static final String CODIGO = "HISTORICO_MUNICIPAL";

    @Override public String codigo() { return CODIGO; }
    @Override public String nome() { return "Histórico oficial de desastres no município"; }
    @Override public CategoriaFator categoria() { return CategoriaFator.HISTORICO; }
    @Override public double peso() { return 0.75; }
    @Override public String codigoFonte() { return "S2ID_RECONHECIMENTOS"; }

    @Override
    public AvaliacaoFator avaliar(ContextoAnalise ctx) {
        if (Texto.vazio(ctx.codigoIbge())) {
            return indisponivel("O código IBGE do município não pôde ser identificado, então os registros municipais não foram consultados.");
        }
        Dado<HistoricoMunicipal> dado = ctx.historicoMunicipal();
        if (!dado.disponivel()) {
            return indisponivel("Não foi possível consultar o histórico municipal (" + dado.motivoIndisponibilidade() + ").");
        }
        HistoricoMunicipal h = dado.valor();
        int anos = ctx.parametros().anosHistorico();
        if (!h.baseImportadaParaUf()) {
            return indisponivel("Os registros oficiais do S2iD/Atlas Digital ainda não foram importados para " + ctx.uf()
                    + ". Sem essa base, o histórico municipal não é avaliado.");
        }
        List<Ocorrencia> registros = h.registros();
        int n = registros.size();
        if (n == 0) {
            return avaliado(0, "Nenhum registro oficial em " + anos + " anos",
                    "Não constam registros oficiais de inundação, enxurrada ou alagamento para o município nos últimos "
                            + anos + " anos na base importada.", dado.obtidoEm());
        }
        String anosComEvento = registros.stream()
                .map(o -> String.valueOf(o.getDataOcorrencia().getYear()))
                .distinct()
                .limit(8)
                .collect(Collectors.joining(", "));
        int pontuacao = n >= 5 ? 2 : 1;
        String explicacao = "O município tem " + n + " registro(s) oficial(is) de desastres hidrológicos nos últimos "
                + anos + " anos (anos mais recentes: " + anosComEvento + "). São registros de âmbito municipal: indicam "
                + "que a cidade é afetada por eventos, mas não que este endereço específico foi atingido.";
        return avaliado(pontuacao, n + " registro(s) municipal(is) desde " + (java.time.LocalDate.now().getYear() - anos),
                explicacao, dado.obtidoEm());
    }
}
