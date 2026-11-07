package com.alertalar.service.analise.fatores;

import com.alertalar.entity.Ocorrencia;
import com.alertalar.entity.enums.CategoriaFator;
import com.alertalar.entity.enums.TipoEvento;
import com.alertalar.service.analise.AvaliacaoFator;
import com.alertalar.service.analise.ContextoAnalise;
import com.alertalar.service.analise.ContextoAnalise.HistoricoMunicipal;
import com.alertalar.service.analise.Dado;
import com.alertalar.service.analise.FatorRisco;
import com.alertalar.util.Texto;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Registros oficiais de desastres ligados a chuva no MUNICÍPIO (Atlas Digital / S2iD):
 * inundações, enxurradas, alagamentos e chuvas intensas.
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
            return indisponivel("Não há registros oficiais do Atlas Digital/S2iD carregados para " + ctx.uf()
                    + ". Sem essa base, o histórico municipal não é avaliado.");
        }
        List<Ocorrencia> registros = h.registros();
        int n = registros.size();
        if (n == 0) {
            return avaliado(0, "Nenhum registro oficial em " + anos + " anos",
                    "Não constam registros oficiais de inundação, enxurrada, alagamento ou chuvas intensas para o município nos últimos "
                            + anos + " anos na base importada.", dado.obtidoEm());
        }
        String anosComEvento = registros.stream()
                .map(o -> String.valueOf(o.getDataOcorrencia().getYear()))
                .distinct()
                .limit(8)
                .collect(Collectors.joining(", "));
        Map<TipoEvento, Long> porTipo = registros.stream()
                .collect(Collectors.groupingBy(Ocorrencia::getTipoEvento, () -> new EnumMap<>(TipoEvento.class), Collectors.counting()));
        String detalhamento = porTipo.entrySet().stream()
                .map(e -> e.getValue() + " " + rotuloPlural(e.getKey(), e.getValue()))
                .collect(Collectors.joining(", "));
        long reconhecidos = registros.stream().filter(o -> o.getDescricao() != null
                && o.getDescricao().contains("reconhecida pela União")).count();
        int pontuacao = n >= 5 ? 2 : 1;
        String explicacao = "O município tem " + n + " registro(s) oficial(is) de desastres ligados a chuva nos últimos "
                + anos + " anos (" + detalhamento + ")" + (reconhecidos > 0 ? ", " + reconhecidos
                + " com situação de emergência ou calamidade reconhecida pela União" : "")
                + ". Anos mais recentes: " + anosComEvento + ". São registros de âmbito municipal: mostram que a cidade "
                + "é atingida por esses eventos, mas não que este endereço específico foi afetado.";
        return avaliado(pontuacao, n + " registro(s) oficial(is) no município desde " + (java.time.LocalDate.now().getYear() - anos),
                explicacao, dado.obtidoEm());
    }

    private static String rotuloPlural(TipoEvento tipo, long n) {
        boolean um = n == 1;
        return switch (tipo) {
            case INUNDACAO -> um ? "inundação" : "inundações";
            case ENXURRADA -> um ? "enxurrada" : "enxurradas";
            case ALAGAMENTO -> um ? "alagamento" : "alagamentos";
            case CHUVA_INTENSA -> um ? "registro de chuvas intensas" : "registros de chuvas intensas";
            case OUTRO -> um ? "outro evento" : "outros eventos";
        };
    }
}
