package com.alertalar.service.analise.fatores;

import com.alertalar.entity.enums.CategoriaFator;
import com.alertalar.integration.overpass.OverpassClient.CursoDagua;
import com.alertalar.service.analise.AvaliacaoFator;
import com.alertalar.service.analise.ContextoAnalise;
import com.alertalar.service.analise.Dado;
import com.alertalar.service.analise.FatorRisco;
import com.alertalar.service.analise.ProximidadeAgua;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Distância até rios, canais e corpos d'água (principais) e até córregos, valas e drenos
 * (secundários), com base nas geometrias do OpenStreetMap.
 */
@Component
@Order(2)
public class FatorProximidadeAgua implements FatorRisco {

    public static final String CODIGO = "PROXIMIDADE_AGUA";

    @Override public String codigo() { return CODIGO; }
    @Override public String nome() { return "Proximidade de rios, córregos e corpos d'água"; }
    @Override public CategoriaFator categoria() { return CategoriaFator.SUSCETIBILIDADE; }
    @Override public double peso() { return 2.0; }
    @Override public String codigoFonte() { return "OSM_CURSOS_AGUA"; }

    @Override
    public AvaliacaoFator avaliar(ContextoAnalise ctx) {
        Dado<List<CursoDagua>> dado = ctx.cursosDagua();
        int raio = ctx.parametros().raioCursosAguaMetros();
        if (!dado.disponivel()) {
            return indisponivel("Não foi possível consultar os cursos d'água do OpenStreetMap nesta consulta ("
                    + dado.motivoIndisponibilidade() + ").");
        }
        List<CursoDagua> cursos = dado.valor();
        Optional<ProximidadeAgua> principal = ProximidadeAgua.maisProximo(ctx.latitude(), ctx.longitude(), cursos, CursoDagua::principal);
        Optional<ProximidadeAgua> secundario = ProximidadeAgua.maisProximo(ctx.latitude(), ctx.longitude(), cursos, c -> !c.principal());

        if (principal.isEmpty() && secundario.isEmpty()) {
            return avaliado(0, "Nenhum curso d'água em " + raio + " m",
                    "Nenhum rio, córrego, canal ou corpo d'água está mapeado no OpenStreetMap num raio de " + raio
                            + " m. Observação: córregos pequenos ou canalizados podem não estar mapeados.",
                    dado.obtidoEm());
        }

        int pontosPrincipal = principal.map(p -> pontuarPrincipal(p.distanciaMetros())).orElse(0);
        int pontosSecundario = secundario.map(p -> pontuarSecundario(p.distanciaMetros())).orElse(0);
        int pontuacao = Math.max(pontosPrincipal, pontosSecundario);

        StringBuilder valor = new StringBuilder();
        StringBuilder explicacao = new StringBuilder();
        principal.ifPresent(p -> {
            valor.append(capitalizar(p.curso().descricao())).append(" a ").append(Math.round(p.distanciaMetros())).append(" m");
            explicacao.append(p.distanciaMetros() == 0
                    ? "O ponto está sobre a área de " + p.curso().descricao() + ". "
                    : "O curso/corpo d'água principal mais próximo é " + p.curso().descricao() + ", a cerca de "
                    + Math.round(p.distanciaMetros()) + " m. ");
        });
        secundario.ifPresent(s -> {
            if (!valor.isEmpty()) {
                valor.append("; ");
            }
            valor.append(capitalizar(s.curso().descricao())).append(" a ").append(Math.round(s.distanciaMetros())).append(" m");
            explicacao.append("O córrego/vala/canal de drenagem mais próximo é ").append(s.curso().descricao())
                    .append(", a cerca de ").append(Math.round(s.distanciaMetros())).append(" m. ");
        });
        explicacao.append(switch (pontuacao) {
            case 3 -> "Imóveis muito próximos de rios e corpos d'água estão mais expostos a cheias.";
            case 2 -> "A distância indica exposição moderada a transbordamentos.";
            case 1 -> "A distância indica exposição baixa, mas não nula, a transbordamentos.";
            default -> "A distância até cursos d'água é grande o bastante para não indicar exposição direta a transbordamentos.";
        });
        explicacao.append(" Fonte: geometrias do OpenStreetMap, cuja completude varia por região.");
        return avaliado(pontuacao, valor.toString(), explicacao.toString(), dado.obtidoEm());
    }

    /** Rios, canais e corpos d'água. */
    static int pontuarPrincipal(double distancia) {
        if (distancia <= 150) return 3;
        if (distancia <= 400) return 2;
        if (distancia <= 1000) return 1;
        return 0;
    }

    /** Córregos, valas e drenos (menor volume, mas transbordam com chuva forte). */
    static int pontuarSecundario(double distancia) {
        if (distancia <= 50) return 2;
        if (distancia <= 150) return 1;
        return 0;
    }

    private static String capitalizar(String s) {
        return s == null || s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
