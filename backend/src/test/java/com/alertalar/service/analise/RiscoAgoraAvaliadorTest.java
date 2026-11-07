package com.alertalar.service.analise;

import com.alertalar.dto.SituacaoAtualResponse.Chuva;
import com.alertalar.dto.SituacaoAtualResponse.RiscoAgora;
import com.alertalar.dto.SituacaoAtualResponse.Vazao;
import com.alertalar.entity.enums.ClassificacaoRisco;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Risco de agora: chuva do momento (limites do INMET) combinada com a suscetibilidade do local. */
class RiscoAgoraAvaliadorTest {

    /** Chuva com: últimas 24 h, últimas 72 h, maior hora passada, próximas 24 h, maior hora futura, maior 24 h entre 24 e 72 h. */
    private static Chuva chuva(double p24, double p72, double horaP, double f24, double horaF, double dias2e3) {
        return new Chuva(true, null, p24, p72, horaP, f24, f24 + dias2e3, horaF, dias2e3, "teste");
    }

    private static final Vazao SEM_RIO = new Vazao(false, "sem rio", null, null, null, null, "teste", "");

    @Test
    @DisplayName("Sem chuva: risco agora é baixo mesmo em local de alta suscetibilidade (o caso relatado)")
    void semChuvaLocalAlto() {
        RiscoAgora r = RiscoAgoraAvaliador.avaliar(chuva(0, 0, 0, 0.4, 0.2, 0), SEM_RIO, ClassificacaoRisco.ALTO, true);
        assertEquals("BAIXO", r.nivel());
        assertEquals("Baixo agora", r.rotulo());
        assertEquals(0, r.nivelChuva());
        assertTrue(r.resumo().contains("tende a ser atingido quando chove forte"), r.resumo());
        assertTrue(r.motivos().get(0).startsWith("Sem chuva forte"), r.motivos().toString());
        assertNull(r.proximosDias());
    }

    // descrição, últimas 24 h, 72 h, hora passada, próximas 24 h, hora futura, nível esperado da chuva
    static Stream<Arguments> casosDeChuva() {
        return Stream.of(
                Arguments.of("garoa", 5, 8, 1, 3, 1, 0),
                Arguments.of("29 mm em 24 h", 29, 29, 4, 0, 0, 0),
                Arguments.of("30 mm em 24 h (piso)", 30, 30, 4, 0, 0, 1),
                Arguments.of("20 mm em 1 h previsto", 0, 0, 0, 22, 20, 1),
                Arguments.of("50 mm previstos em 24 h", 0, 0, 0, 50, 8, 2),
                Arguments.of("30 mm em 1 h", 35, 35, 30, 0, 0, 2),
                Arguments.of("100 mm em 24 h ainda é 2", 100, 100, 10, 0, 0, 2),
                Arguments.of("mais de 100 mm em 24 h", 101, 101, 10, 0, 0, 3),
                Arguments.of("mais de 60 mm em 1 h", 61, 61, 61, 0, 0, 3),
                Arguments.of("100 mm em 72 h", 10, 100, 2, 0, 0, 1),
                Arguments.of("150 mm em 72 h", 10, 150, 2, 0, 0, 2));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("casosDeChuva")
    void nivelDaChuva(String descricao, double p24, double p72, double horaP, double f24, double horaF, int esperado) {
        assertEquals(esperado, RiscoAgoraAvaliador.nivelChuva(chuva(p24, p72, horaP, f24, horaF, 0), new ArrayList<>()), descricao);
    }

    static Stream<Arguments> combinacoes() {
        return Stream.of(
                Arguments.of(1, ClassificacaoRisco.BAIXO, "BAIXO"),
                Arguments.of(1, ClassificacaoRisco.MODERADO, "ATENCAO"),
                Arguments.of(1, ClassificacaoRisco.ALTO, "ALTO"),
                Arguments.of(2, ClassificacaoRisco.BAIXO, "ATENCAO"),
                Arguments.of(2, ClassificacaoRisco.MODERADO, "ALTO"),
                Arguments.of(2, ClassificacaoRisco.ALTO, "MUITO_ALTO"),
                Arguments.of(3, ClassificacaoRisco.BAIXO, "ALTO"),
                Arguments.of(3, ClassificacaoRisco.MODERADO, "MUITO_ALTO"),
                Arguments.of(3, ClassificacaoRisco.ALTO, "MUITO_ALTO"),
                Arguments.of(2, ClassificacaoRisco.DADOS_INSUFICIENTES, "ALTO"));
    }

    @ParameterizedTest(name = "chuva {0} + suscetibilidade {1} = {2}")
    @MethodSource("combinacoes")
    void combinacao(int nivelChuva, ClassificacaoRisco suscetibilidade, String esperado) {
        double[] porNivel = {0, 35, 60, 120};
        RiscoAgora r = RiscoAgoraAvaliador.avaliar(chuva(porNivel[nivelChuva], porNivel[nivelChuva], 5, 0, 0, 0), SEM_RIO,
                suscetibilidade, false);
        assertEquals(nivelChuva, r.nivelChuva());
        assertEquals(esperado, r.nivel());
        assertEquals(suscetibilidade.name(), r.suscetibilidade());
    }

    @Test
    @DisplayName("Sem consulta, a suscetibilidade é tratada como moderada")
    void semConsulta() {
        RiscoAgora r = RiscoAgoraAvaliador.avaliar(chuva(55, 55, 9, 0, 0, 0), SEM_RIO, null, false);
        assertEquals("ALTO", r.nivel());
        assertNull(r.suscetibilidade());
        assertTrue(r.motivos().contains("55 mm de chuva nas últimas 24 horas."), r.motivos().toString());
    }

    @Test
    @DisplayName("Rio próximo com previsão de subir 50% ou mais leva pelo menos a 'atenção'")
    void rioSubindo() {
        Vazao subindo = new Vazao(true, null, 100.0, 90.0, 160.0, "SUBINDO", "teste", "");
        RiscoAgora perto = RiscoAgoraAvaliador.avaliar(chuva(0, 0, 0, 0, 0, 0), subindo, ClassificacaoRisco.BAIXO, true);
        assertEquals("ATENCAO", perto.nivel());
        assertTrue(perto.motivos().get(0).contains("subir cerca de 60%"), perto.motivos().toString());
        RiscoAgora longe = RiscoAgoraAvaliador.avaliar(chuva(0, 0, 0, 0, 0, 0), subindo, ClassificacaoRisco.BAIXO, false);
        assertEquals("BAIXO", longe.nivel());
        Vazao poucaSubida = new Vazao(true, null, 100.0, 90.0, 140.0, "SUBINDO", "teste", "");
        assertEquals("BAIXO", RiscoAgoraAvaliador.avaliar(chuva(0, 0, 0, 0, 0, 0), poucaSubida, ClassificacaoRisco.ALTO, true).nivel());
    }

    @Test
    @DisplayName("Chuva forte prevista para depois de amanhã aparece como aviso dos próximos dias")
    void proximosDias() {
        RiscoAgora r = RiscoAgoraAvaliador.avaliar(chuva(0, 0, 0, 2, 1, 72.5), SEM_RIO, ClassificacaoRisco.MODERADO, false);
        assertEquals("BAIXO", r.nivel());
        assertTrue(r.proximosDias().contains("72,5 mm"), r.proximosDias());
    }

    @Test
    @DisplayName("Sem dados de chuva, o risco agora fica indisponível (nunca vira 'baixo')")
    void indisponivel() {
        RiscoAgora r = RiscoAgoraAvaliador.avaliar(Chuva.indisponivel("fora do ar", "teste"), SEM_RIO, ClassificacaoRisco.ALTO, true);
        assertEquals("INDISPONIVEL", r.nivel());
        assertNull(r.nivelChuva());
        assertTrue(r.resumo().contains("40199"));
    }
}
