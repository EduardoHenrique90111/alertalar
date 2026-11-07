package com.alertalar.service.analise;

import com.alertalar.entity.enums.SituacaoFator;
import com.alertalar.entity.enums.TipoEvento;
import com.alertalar.service.analise.fatores.FatorElevacaoRelativa;
import com.alertalar.service.analise.fatores.FatorHistoricoMunicipal;
import com.alertalar.service.analise.fatores.FatorOcorrenciasLocais;
import com.alertalar.service.analise.fatores.FatorProximidadeAgua;
import com.alertalar.service.analise.fatores.FatorSetorRiscoOficial;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Cada fator é uma regra independente, testada isoladamente. */
class FatoresRiscoTest {

    @Nested
    @DisplayName("Proximidade de água (OpenStreetMap)")
    class Proximidade {
        private final FatorProximidadeAgua fator = new FatorProximidadeAgua();

        @Test
        void rioMuitoProximoPontuaTres() {
            AvaliacaoFator a = fator.avaliar(ContextoTeste.novo().rioA(100).construir());
            assertEquals(SituacaoFator.AVALIADO, a.situacao());
            assertEquals(3, a.pontuacao());
            assertTrue(a.valorObservado().contains("Rio Teste"));
        }

        @Test
        void faixasDeDistancia() {
            assertEquals(2, fator.avaliar(ContextoTeste.novo().rioA(300).construir()).pontuacao());
            assertEquals(1, fator.avaliar(ContextoTeste.novo().rioA(900).construir()).pontuacao());
            assertEquals(0, fator.avaliar(ContextoTeste.novo().rioA(1400).construir()).pontuacao());
        }

        @Test
        void corregoProximoPontuaAteDois() {
            AvaliacaoFator a = fator.avaliar(ContextoTeste.novo().semCursosDagua().corregoA(30).construir());
            assertEquals(2, a.pontuacao());
        }

        @Test
        void semAguaNoRaioPontuaZeroComAvisoDeCompletude() {
            AvaliacaoFator a = fator.avaliar(ContextoTeste.novo().semCursosDagua().construir());
            assertEquals(0, a.pontuacao());
            assertTrue(a.explicacao().contains("podem não estar mapeados"));
        }

        @Test
        void fonteIndisponivelNaoPontua() {
            AvaliacaoFator a = fator.avaliar(ContextoTeste.novo().construir());
            assertEquals(SituacaoFator.INDISPONIVEL, a.situacao());
            assertNull(a.pontuacao());
        }
    }

    @Nested
    @DisplayName("Elevação relativa (Copernicus DEM)")
    class Elevacao {
        private final FatorElevacaoRelativa fator = new FatorElevacaoRelativa();

        @Test
        void quaseNoNivelDaAguaPontuaTres() {
            AvaliacaoFator a = fator.avaliar(ContextoTeste.novo().elevacao(5.0, 4.0, 200.0, 6.0).construir());
            assertEquals(3, a.pontuacao());
        }

        @Test
        void terrenoAltoNaoPontua() {
            AvaliacaoFator a = fator.avaliar(ContextoTeste.novo().elevacao(40.0, 5.0, 200.0, 38.0).construir());
            assertEquals(0, a.pontuacao());
        }

        @Test
        void depressaoLocalPontuaMesmoLongeDaAgua() {
            AvaliacaoFator a = fator.avaliar(ContextoTeste.novo().elevacao(20.0, null, null, 26.0).construir());
            assertEquals(2, a.pontuacao());
            assertTrue(a.explicacao().contains("acumular água"));
        }

        @Test
        void aguaDistanteDemaisNaoEhUsadaComoReferencia() {
            AvaliacaoFator a = fator.avaliar(ContextoTeste.novo().elevacao(10.0, 9.5, 1400.0, 10.0).construir());
            assertEquals(0, a.pontuacao());
        }

        @Test
        void semElevacaoFicaIndisponivel() {
            assertEquals(SituacaoFator.INDISPONIVEL, fator.avaliar(ContextoTeste.novo().construir()).situacao());
        }
    }

    @Nested
    @DisplayName("Setores de risco do SGB")
    class Setores {
        private final FatorSetorRiscoOficial fator = new FatorSetorRiscoOficial();

        @Test
        void dentroDeSetorMuitoAltoForcaAltoRisco() {
            AvaliacaoFator a = fator.avaliar(ContextoTeste.novo().setor("Muito alto", 0, true).construir());
            assertEquals(3, a.pontuacao());
            assertTrue(a.forcaAlto());
            assertTrue(a.explicacao().contains("DENTRO"));
        }

        @Test
        void setorProximoPontuaSemForcar() {
            // centro deslocado ~0,0025° (≈240 m); borda a ~140 m do ponto
            AvaliacaoFator a = fator.avaliar(ContextoTeste.novo().setor("Alto", 0.0025, true).construir());
            assertEquals(1, a.pontuacao());
            assertFalse(a.forcaAlto());
        }

        @Test
        void setorDeDeslizamentoNaoConta() {
            AvaliacaoFator a = fator.avaliar(ContextoTeste.novo().setor("Muito alto", 0, false).construir());
            assertEquals(SituacaoFator.SEM_REGISTRO, a.situacao());
        }

        @Test
        void ausenciaDeSetorNaoEhEvidenciaDeBaixoRisco() {
            AvaliacaoFator a = fator.avaliar(ContextoTeste.novo().semSetores().construir());
            assertEquals(SituacaoFator.SEM_REGISTRO, a.situacao());
            assertNull(a.pontuacao());
            assertTrue(a.explicacao().contains("não indica ausência de risco"));
        }
    }

    @Nested
    @DisplayName("Ocorrências confirmadas nas proximidades")
    class Ocorrencias {
        private final FatorOcorrenciasLocais fator = new FatorOcorrenciasLocais();

        @Test
        void tresOuMaisImpedemBaixoRisco() {
            AvaliacaoFator a = fator.avaliar(ContextoTeste.novo().ocorrencias(4, 120).construir());
            assertEquals(3, a.pontuacao());
            assertTrue(a.minimoModerado());
        }

        @Test
        void umaOcorrenciaPontuaUm() {
            assertEquals(1, fator.avaliar(ContextoTeste.novo().ocorrencias(1, 300).construir()).pontuacao());
        }

        @Test
        void semOcorrenciasNaoPontua() {
            AvaliacaoFator a = fator.avaliar(ContextoTeste.novo().construir());
            assertEquals(SituacaoFator.SEM_REGISTRO, a.situacao());
        }
    }

    @Nested
    @DisplayName("Histórico municipal (S2iD)")
    class HistoricoMunicipal {
        private final FatorHistoricoMunicipal fator = new FatorHistoricoMunicipal();

        @Test
        void baseNaoImportadaFicaIndisponivel() {
            AvaliacaoFator a = fator.avaliar(ContextoTeste.novo().historicoMunicipal(0, false).construir());
            assertEquals(SituacaoFator.INDISPONIVEL, a.situacao());
        }

        @Test
        void muitosRegistrosPontuamNoMaximoDois() {
            AvaliacaoFator a = fator.avaliar(ContextoTeste.novo().historicoMunicipal(12, true).construir());
            assertEquals(2, a.pontuacao());
            assertTrue(a.explicacao().contains("não que este endereço"));
        }

        @Test
        @DisplayName("Chuvas intensas contam como desastre ligado a chuva e o resumo detalha os tipos")
        void chuvasIntensasEReconhecimentos() {
            ContextoTeste ctx = ContextoTeste.novo().historicoMunicipal(3, true);
            AvaliacaoFator a = fator.avaliar(ctx.construir(o -> {
                o.get(0).setTipoEvento(TipoEvento.CHUVA_INTENSA);
                o.get(0).setDescricao("Chuvas intensas registradas pelo município. Situação de emergência ou calamidade reconhecida pela União.");
                o.get(1).setTipoEvento(TipoEvento.INUNDACAO);
            }));
            assertEquals(1, a.pontuacao());
            assertTrue(a.explicacao().contains("1 inundação"), a.explicacao());
            assertTrue(a.explicacao().contains("1 alagamento"), a.explicacao());
            assertTrue(a.explicacao().contains("1 registro de chuvas intensas"), a.explicacao());
            assertTrue(a.explicacao().contains("1 com situação de emergência ou calamidade reconhecida pela União"), a.explicacao());
        }

        @Test
        void baseImportadaSemRegistrosPontuaZero() {
            assertEquals(0, fator.avaliar(ContextoTeste.novo().historicoMunicipal(0, true).construir()).pontuacao());
        }

        @Test
        void semCodigoIbgeFicaIndisponivel() {
            assertEquals(SituacaoFator.INDISPONIVEL,
                    fator.avaliar(ContextoTeste.novo().semCodigoIbge().historicoMunicipal(3, true).construir()).situacao());
        }
    }
}
