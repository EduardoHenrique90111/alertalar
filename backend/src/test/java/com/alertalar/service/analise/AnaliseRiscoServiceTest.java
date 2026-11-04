package com.alertalar.service.analise;

import com.alertalar.entity.enums.ClassificacaoRisco;
import com.alertalar.service.analise.fatores.FatorElevacaoRelativa;
import com.alertalar.service.analise.fatores.FatorHistoricoMunicipal;
import com.alertalar.service.analise.fatores.FatorOcorrenciasLocais;
import com.alertalar.service.analise.fatores.FatorProximidadeAgua;
import com.alertalar.service.analise.fatores.FatorSetorRiscoOficial;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Classificação de risco consolidada a partir dos fatores reais. */
class AnaliseRiscoServiceTest {

    private final AnaliseRiscoService servico = new AnaliseRiscoService(List.of(
            new FatorSetorRiscoOficial(), new FatorProximidadeAgua(), new FatorElevacaoRelativa(),
            new FatorOcorrenciasLocais(), new FatorHistoricoMunicipal()));

    @Test
    @DisplayName("Terreno baixo junto a rio, com histórico municipal, resulta em alto risco")
    void altoRisco() {
        ResultadoAnalise r = servico.analisar(ContextoTeste.novo().rioA(120).elevacao(4.0, 3.0, 120.0, 6.0)
                .semSetores().historicoMunicipal(8, true).construir());
        assertEquals(ClassificacaoRisco.ALTO, r.classificacao());
        assertNotNull(r.indice());
        assertTrue(r.indice() >= 63);
    }

    @Test
    @DisplayName("Ponto alto, longe de água, resulta em baixo risco com ressalva explícita")
    void baixoRisco() {
        ResultadoAnalise r = servico.analisar(ContextoTeste.novo().semCursosDagua().elevacao(60.0, null, null, 58.0)
                .semSetores().historicoMunicipal(1, true).construir());
        assertEquals(ClassificacaoRisco.BAIXO, r.classificacao());
        assertTrue(r.justificativa().contains("NÃO significa que o local esteja livre"));
    }

    @Test
    @DisplayName("Fatores intermediários resultam em risco moderado")
    void moderado() {
        ResultadoAnalise r = servico.analisar(ContextoTeste.novo().rioA(350).elevacao(12.0, 5.0, 350.0, 12.0)
                .semSetores().construir());
        // proximidade 2, elevação 1 (desnível 7 m) → média 1,5
        assertEquals(ClassificacaoRisco.MODERADO, r.classificacao());
    }

    @Test
    @DisplayName("Sem nenhuma fonte disponível, a classificação é dados insuficientes")
    void dadosInsuficientes() {
        ResultadoAnalise r = servico.analisar(ContextoTeste.novo().construir());
        assertEquals(ClassificacaoRisco.DADOS_INSUFICIENTES, r.classificacao());
        assertNull(r.indice());
    }

    @Test
    @DisplayName("Só histórico municipal (sem fator geográfico) não basta para classificar")
    void semFatorGeografico() {
        ResultadoAnalise r = servico.analisar(ContextoTeste.novo().historicoMunicipal(10, true).ocorrencias(1, 100).construir());
        assertEquals(ClassificacaoRisco.DADOS_INSUFICIENTES, r.classificacao());
    }

    @Test
    @DisplayName("Dentro de setor de risco muito alto do SGB é alto risco, mesmo com outras fontes fora do ar")
    void setorOficialDetermina() {
        ResultadoAnalise r = servico.analisar(ContextoTeste.novo().setor("Muito alto", 0, true).construir());
        assertEquals(ClassificacaoRisco.ALTO, r.classificacao());
        assertTrue(r.justificativa().contains("SGB"));
    }

    @Test
    @DisplayName("Três ocorrências confirmadas impedem a classificação baixo risco")
    void ocorrenciasRepetidas() {
        ResultadoAnalise r = servico.analisar(ContextoTeste.novo().semCursosDagua().elevacao(60.0, null, null, 58.0)
                .ocorrencias(3, 200).construir());
        // média: (0*2 + 0*2 + 3*2) / 6 = 1,0 → já moderado; o mínimo garante que não seja baixo
        assertFalse(r.classificacao() == ClassificacaoRisco.BAIXO);
    }

    @Test
    @DisplayName("A justificativa separa risco mapeado, suscetibilidade e histórico")
    void justificativaPorDimensao() {
        ResultadoAnalise r = servico.analisar(ContextoTeste.novo().rioA(120).elevacao(4.0, 3.0, 120.0, 6.0).construir());
        assertTrue(r.justificativa().contains("Risco mapeado por órgão oficial"));
        assertTrue(r.justificativa().contains("Suscetibilidade geográfica"));
        assertTrue(r.justificativa().contains("Risco histórico"));
        assertTrue(r.justificativa().contains("não é laudo técnico"));
        assertEquals(5, r.fatores().size());
    }
}
