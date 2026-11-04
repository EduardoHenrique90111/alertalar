package com.alertalar.service;

import com.alertalar.dto.ImportacaoResultado;
import com.alertalar.entity.FonteDados;
import com.alertalar.entity.Ocorrencia;
import com.alertalar.entity.enums.NivelLocalizacao;
import com.alertalar.entity.enums.OrigemOcorrencia;
import com.alertalar.entity.enums.StatusIntegracao;
import com.alertalar.entity.enums.StatusValidacao;
import com.alertalar.entity.enums.TipoFonte;
import com.alertalar.integration.ibge.IbgeLocalidadesClient;
import com.alertalar.integration.s2id.LeitorRegistrosOficiaisCsv;
import com.alertalar.repository.FonteDadosRepository;
import com.alertalar.repository.OcorrenciaRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Importação de registros oficiais municipais com proteção contra duplicidade. */
@ExtendWith(MockitoExtension.class)
class ImportacaoServiceTest {

    @Mock OcorrenciaRepository ocorrencias;
    @Mock FonteDadosRepository fontes;
    @Mock IbgeLocalidadesClient ibge;

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("Importa como OFICIAL/CONFIRMADA/MUNICIPIO, ignora duplicados e atualiza a data da fonte")
    void importa() {
        FonteDados fonte = new FonteDados("S2ID_RECONHECIMENTOS", "S2iD", TipoFonte.IMPORTACAO_OFICIAL, StatusIntegracao.IMPORTACAO_MANUAL);
        when(fontes.findByCodigo("S2ID_RECONHECIMENTOS")).thenReturn(Optional.of(fonte));
        when(ocorrencias.existsByFonteAndIdentificadorExterno(eq(fonte), any())).thenAnswer(inv -> inv.getArgument(1).equals("PROT:P-2"));
        when(ibge.codigoMunicipio("RS", "Canoas")).thenReturn(Optional.of("4304606"));
        String csv = """
                Protocolo;UF;Município;Data do Evento;COBRADE
                P-1;RS;Canoas;01/05/2024;1.2.1.0.0
                P-2;RS;Canoas;02/05/2024;1.2.1.0.0
                P-1;RS;Canoas;01/05/2024;1.2.1.0.0
                """;
        ImportacaoService servico = new ImportacaoService(new LeitorRegistrosOficiaisCsv(), ocorrencias, fontes, ibge);

        ImportacaoResultado r = servico.importarRegistrosOficiais(csv.getBytes(StandardCharsets.UTF_8), "s2id.csv", null);

        assertEquals(1, r.importados());
        assertEquals(2, r.duplicados());
        ArgumentCaptor<List<Ocorrencia>> captor = ArgumentCaptor.forClass(List.class);
        verify(ocorrencias).saveAll(captor.capture());
        Ocorrencia o = captor.getValue().get(0);
        assertEquals(OrigemOcorrencia.OFICIAL, o.getOrigem());
        assertEquals(StatusValidacao.CONFIRMADA, o.getStatusValidacao());
        assertEquals(NivelLocalizacao.MUNICIPIO, o.getNivelLocalizacao());
        assertEquals("4304606", o.getCodigoIbge());
        assertEquals("PROT:P-1", o.getIdentificadorExterno());
        assertNotNull(fonte.getDataAtualizacao());
    }
}
