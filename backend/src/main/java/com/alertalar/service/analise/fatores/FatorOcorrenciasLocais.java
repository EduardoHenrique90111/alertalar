package com.alertalar.service.analise.fatores;

import com.alertalar.entity.enums.CategoriaFator;
import com.alertalar.entity.enums.OrigemOcorrencia;
import com.alertalar.entity.enums.SituacaoFator;
import com.alertalar.service.analise.AvaliacaoFator;
import com.alertalar.service.analise.ContextoAnalise;
import com.alertalar.service.analise.ContextoAnalise.OcorrenciaProxima;
import com.alertalar.service.analise.Dado;
import com.alertalar.service.analise.FatorRisco;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Ocorrências pontuais CONFIRMADAS perto do endereço: registros oficiais com coordenadas
 * e relatos de usuários já validados pela moderação. Relatos pendentes nunca entram.
 */
@Component
@Order(4)
public class FatorOcorrenciasLocais implements FatorRisco {

    public static final String CODIGO = "OCORRENCIAS_LOCAIS";

    @Override public String codigo() { return CODIGO; }
    @Override public String nome() { return "Ocorrências registradas nas proximidades"; }
    @Override public CategoriaFator categoria() { return CategoriaFator.HISTORICO; }
    @Override public double peso() { return 2.0; }
    @Override public String codigoFonte() { return "ALERTALAR_RELATOS"; }

    @Override
    public AvaliacaoFator avaliar(ContextoAnalise ctx) {
        Dado<List<OcorrenciaProxima>> dado = ctx.ocorrenciasProximas();
        int raio = ctx.parametros().raioOcorrenciasMetros();
        int anos = ctx.parametros().anosHistorico();
        if (!dado.disponivel()) {
            return indisponivel("Não foi possível consultar as ocorrências registradas (" + dado.motivoIndisponibilidade() + ").");
        }
        List<OcorrenciaProxima> lista = dado.valor();
        if (lista.isEmpty()) {
            return semRegistro("Nenhuma ocorrência confirmada em " + raio + " m",
                    "Não há ocorrências confirmadas registradas no AlertaLar num raio de " + raio + " m nos últimos "
                            + anos + " anos. A base depende de registros oficiais com localização e de relatos validados; "
                            + "a ausência de registros não indica ausência de risco.", dado.obtidoEm());
        }
        long oficiais = lista.stream().filter(o -> o.ocorrencia().getOrigem() == OrigemOcorrencia.OFICIAL).count();
        long relatos = lista.size() - oficiais;
        double maisProxima = lista.stream().mapToDouble(OcorrenciaProxima::distanciaMetros).min().orElse(0);
        int n = lista.size();
        int pontuacao = n >= 3 ? 3 : n;
        String valor = n + (n == 1 ? " ocorrência confirmada" : " ocorrências confirmadas") + " em " + raio
                + " m (mais próxima a " + Math.round(maisProxima) + " m)";
        String explicacao = "Foram encontradas " + n + " ocorrência(s) de alagamento/enchente confirmada(s) num raio de "
                + raio + " m nos últimos " + anos + " anos: " + oficiais + " registro(s) oficial(is) e " + relatos
                + " relato(s) de usuários validado(s). A mais próxima fica a cerca de " + Math.round(maisProxima) + " m. "
                + (n >= 3 ? "Ocorrências repetidas na vizinhança indicam histórico consistente de alagamentos."
                : "Ocorrências na vizinhança indicam que a área já foi afetada.");
        return new AvaliacaoFator(codigo(), nome(), categoria(), SituacaoFator.AVALIADO, pontuacao, peso(), valor,
                explicacao, codigoFonte(), dado.obtidoEm(), false, n >= 3);
    }
}
