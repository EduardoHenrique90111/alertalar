package com.alertalar.service.analise;

import com.alertalar.entity.enums.CategoriaFator;
import com.alertalar.entity.enums.ClassificacaoRisco;
import com.alertalar.service.analise.fatores.FatorElevacaoRelativa;
import com.alertalar.service.analise.fatores.FatorProximidadeAgua;
import com.alertalar.service.analise.fatores.FatorSetorRiscoOficial;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Consolida os fatores independentes em uma classificação.
 *
 * <p><b>Metodologia (versão 1.0)</b></p>
 * <ol>
 *   <li>Cada fator devolve uma pontuação de 0 (sem indício) a 3 (indício forte), ou fica fora
 *       da conta quando não há dado (INDISPONIVEL) ou quando a ausência de registro não pode ser
 *       interpretada como evidência (SEM_REGISTRO).</li>
 *   <li>É obrigatório pelo menos um fator geográfico avaliado e pelo menos dois fatores avaliados;
 *       caso contrário, a classificação é "Dados insuficientes".</li>
 *   <li>Média ponderada M (0 a 3): M ≥ 1,9 → alto; M ≥ 1,0 → moderado; abaixo → baixo.</li>
 *   <li>Regras de evidência: estar dentro de setor de risco alto/muito alto do SGB → alto;
 *       três ou mais ocorrências confirmadas nas proximidades → no mínimo moderado.</li>
 * </ol>
 * A classificação indica a presença de indícios nos dados disponíveis. Não é laudo técnico,
 * estudo hidrológico nem previsão meteorológica.
 */
@Service
public class AnaliseRiscoService {

    public static final String VERSAO_METODOLOGIA = "1.0";
    static final double LIMIAR_ALTO = 1.9;
    static final double LIMIAR_MODERADO = 1.0;
    private static final Set<String> FATORES_GEOGRAFICOS = Set.of(
            FatorProximidadeAgua.CODIGO, FatorElevacaoRelativa.CODIGO, FatorSetorRiscoOficial.CODIGO);

    private final List<FatorRisco> fatores;

    public AnaliseRiscoService(List<FatorRisco> fatores) {
        this.fatores = List.copyOf(fatores);
    }

    public ResultadoAnalise analisar(ContextoAnalise contexto) {
        List<AvaliacaoFator> avaliacoes = fatores.stream().map(f -> f.avaliar(contexto)).toList();
        return consolidar(avaliacoes);
    }

    ResultadoAnalise consolidar(List<AvaliacaoFator> avaliacoes) {
        List<AvaliacaoFator> avaliados = avaliacoes.stream().filter(AvaliacaoFator::avaliado).toList();
        boolean temGeografico = avaliados.stream().anyMatch(a -> FATORES_GEOGRAFICOS.contains(a.codigo()));
        boolean forcaAlto = avaliados.stream().anyMatch(AvaliacaoFator::forcaAlto);
        boolean minimoModerado = avaliados.stream().anyMatch(AvaliacaoFator::minimoModerado);

        Double media = null;
        if (!avaliados.isEmpty()) {
            double somaPesos = avaliados.stream().mapToDouble(AvaliacaoFator::peso).sum();
            double soma = avaliados.stream().mapToDouble(a -> a.pontuacao() * a.peso()).sum();
            media = soma / somaPesos;
        }

        ClassificacaoRisco classificacao;
        if (forcaAlto) {
            classificacao = ClassificacaoRisco.ALTO;
        } else if (avaliados.size() < 2 || !temGeografico) {
            classificacao = ClassificacaoRisco.DADOS_INSUFICIENTES;
        } else if (media >= LIMIAR_ALTO) {
            classificacao = ClassificacaoRisco.ALTO;
        } else if (media >= LIMIAR_MODERADO) {
            classificacao = ClassificacaoRisco.MODERADO;
        } else {
            classificacao = minimoModerado ? ClassificacaoRisco.MODERADO : ClassificacaoRisco.BAIXO;
        }

        Double indice = (classificacao == ClassificacaoRisco.DADOS_INSUFICIENTES || media == null)
                ? null : Math.round(media / 3.0 * 1000) / 10.0;
        String justificativa = montarJustificativa(classificacao, media, avaliacoes, avaliados, forcaAlto, minimoModerado, temGeografico);
        return new ResultadoAnalise(classificacao, indice, avaliacoes, justificativa, VERSAO_METODOLOGIA);
    }

    private String montarJustificativa(ClassificacaoRisco c, Double media, List<AvaliacaoFator> todos,
                                       List<AvaliacaoFator> avaliados, boolean forcaAlto, boolean minimoModerado,
                                       boolean temGeografico) {
        StringBuilder sb = new StringBuilder();
        sb.append(textoClassificacao(c)).append("\n\n");
        if (c == ClassificacaoRisco.DADOS_INSUFICIENTES) {
            sb.append(avaliados.isEmpty()
                    ? "Nenhum fator pôde ser avaliado com dados reais nesta consulta."
                    : !temGeografico
                    ? "Nenhum fator geográfico (proximidade de água, elevação ou setor de risco) pôde ser avaliado."
                    : "Apenas um fator pôde ser avaliado, o que não é suficiente para uma classificação.")
                    .append(" As fontes indisponíveis estão listadas abaixo; tente novamente mais tarde.\n\n");
        } else {
            sb.append(String.format(java.util.Locale.ROOT, "Média ponderada dos %d fator(es) avaliado(s): %.2f numa escala de 0 a 3",
                    avaliados.size(), media)).append(" (alto a partir de 1,9; moderado a partir de 1,0).");
            if (forcaAlto) {
                sb.append(" O ponto está dentro de setor de risco hidrológico alto ou muito alto do SGB, o que determina a classificação \"alto risco\".");
            } else if (minimoModerado && c == ClassificacaoRisco.MODERADO && media < LIMIAR_MODERADO) {
                sb.append(" Há ocorrências confirmadas repetidas nas proximidades, por isso a classificação mínima é \"risco moderado\".");
            }
            sb.append("\n\n");
        }
        for (CategoriaFator cat : CategoriaFator.values()) {
            List<AvaliacaoFator> daCategoria = todos.stream().filter(a -> a.categoria() == cat).toList();
            if (daCategoria.isEmpty()) {
                continue;
            }
            sb.append(switch (cat) {
                case RISCO_MAPEADO -> "Risco mapeado por órgão oficial:";
                case SUSCETIBILIDADE -> "Suscetibilidade geográfica:";
                case HISTORICO -> "Risco histórico:";
            }).append('\n');
            sb.append(daCategoria.stream()
                    .map(a -> "- " + a.nome() + " [" + rotuloSituacao(a) + "]: " + a.explicacao())
                    .collect(Collectors.joining("\n"))).append("\n\n");
        }
        sb.append("Esta avaliação indica a presença de indícios nos dados disponíveis e não é laudo técnico, estudo "
                + "hidrológico nem previsão do tempo. Situações de emergência: Defesa Civil 199 ou Bombeiros 193.");
        return sb.toString().trim();
    }

    private static String rotuloSituacao(AvaliacaoFator a) {
        return switch (a.situacao()) {
            case AVALIADO -> "pontuação " + a.pontuacao() + " de 3";
            case SEM_REGISTRO -> "sem registro, não pontua";
            case INDISPONIVEL -> "fonte indisponível, não pontua";
        };
    }

    public static String textoClassificacao(ClassificacaoRisco c) {
        return switch (c) {
            case ALTO -> "ALTO RISCO: os dados disponíveis reúnem indícios fortes de que o local é suscetível a "
                    + "enchentes ou alagamentos.";
            case MODERADO -> "RISCO MODERADO: os dados disponíveis indicam alguns fatores de suscetibilidade a "
                    + "enchentes ou alagamentos.";
            case BAIXO -> "BAIXO RISCO: os dados disponíveis não indicam suscetibilidade elevada neste ponto. Isso NÃO "
                    + "significa que o local esteja livre de alagamentos: chuvas extremas, falhas de drenagem e dados "
                    + "incompletos podem alterar esta avaliação.";
            case DADOS_INSUFICIENTES -> "DADOS INSUFICIENTES: não há dados confiáveis suficientes para classificar "
                    + "este endereço.";
        };
    }
}
