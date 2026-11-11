package com.alertalar.service;

/**
 * Andamento do cálculo da rota segura, etapa por etapa. A tela usa esses avisos para mostrar
 * sobre o mapa o que está sendo consultado (e em qual fonte) enquanto o caminho não chega:
 * cada etapa é uma consulta real a um serviço externo, nada é estimado pelo tempo.
 *
 * <p>Os avisos podem chegar de threads diferentes (altura e áreas de risco são consultadas ao
 * mesmo tempo); quem implementa precisa aceitar chamadas simultâneas.</p>
 */
@FunctionalInterface
public interface ProgressoRota {

    ProgressoRota NENHUM = evento -> { };

    void informar(Evento evento);

    /**
     * {@code true} quando a pessoa desistiu (cancelou ou fechou a página): o cálculo para antes da
     * próxima consulta externa, sem gastar o limite de uso dos serviços públicos.
     */
    default boolean cancelado() {
        return false;
    }

    /** Etapas do cálculo, na ordem em que aparecem para a pessoa. */
    enum Etapa {
        ALTURA("Altura da partida e do destino", "o Copernicus DEM (via Open-Meteo)"),
        SETORES("Áreas de risco no trajeto", "o Serviço Geológico do Brasil (SGB)"),
        CAMINHO("Caminho pelas ruas", "o Valhalla (ruas do OpenStreetMap)"),
        PERFIL("Altura ao longo do caminho", "o Copernicus DEM (via Open-Meteo)");

        private final String rotulo;
        private final String fonte;

        Etapa(String rotulo, String fonte) {
            this.rotulo = rotulo;
            this.fonte = fonte;
        }

        public String rotulo() {
            return rotulo;
        }

        public String fonte() {
            return fonte;
        }
    }

    enum Estado {
        /** Ainda vai acontecer. */
        PENDENTE,
        /** Consulta enviada, aguardando a resposta do serviço. */
        EM_ANDAMENTO,
        CONCLUIDA,
        FALHOU
    }

    /**
     * @param etapa   {@link Etapa} (ALTURA, SETORES, CAMINHO ou PERFIL)
     * @param estado  {@link Estado}
     * @param rotulo  o que está sendo feito, em português
     * @param fonte   serviço consultado, já com artigo para a frase "Consultando o …" da tela
     * @param detalhe complemento (ex.: "desviando de 3 áreas de risco", "1,2 km pelas ruas"); pode ser nulo
     */
    record Evento(String etapa, String estado, String rotulo, String fonte, String detalhe) {
    }

    /** O cálculo foi interrompido porque a pessoa desistiu. */
    final class CalculoCanceladoException extends RuntimeException {
        public CalculoCanceladoException() {
            super("Cálculo da rota cancelado.", null, false, false);
        }
    }
}
