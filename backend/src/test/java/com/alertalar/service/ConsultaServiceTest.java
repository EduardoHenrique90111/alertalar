package com.alertalar.service;

import com.alertalar.dto.EnderecoRequest;
import com.alertalar.entity.Consulta;
import com.alertalar.entity.Endereco;
import com.alertalar.entity.FonteDados;
import com.alertalar.entity.enums.ClassificacaoRisco;
import com.alertalar.entity.enums.PrecisaoGeocodificacao;
import com.alertalar.entity.enums.StatusIntegracao;
import com.alertalar.entity.enums.TipoFonte;
import com.alertalar.repository.ConsultaRepository;
import com.alertalar.service.analise.AnaliseRiscoService;
import com.alertalar.service.analise.ContextoTeste;
import com.alertalar.service.analise.fatores.FatorElevacaoRelativa;
import com.alertalar.service.analise.fatores.FatorHistoricoMunicipal;
import com.alertalar.service.analise.fatores.FatorOcorrenciasLocais;
import com.alertalar.service.analise.fatores.FatorProximidadeAgua;
import com.alertalar.service.analise.fatores.FatorSetorRiscoOficial;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Processamento completo de uma consulta (orquestração), com dependências simuladas. */
@ExtendWith(MockitoExtension.class)
class ConsultaServiceTest {

    @Mock EnderecoService enderecoService;
    @Mock ColetaDadosService coleta;
    @Mock ConsultaRepository consultas;
    @Mock FonteDadosService fontes;

    private final JsonMapper json = JsonMapper.builder().build();
    private final AnaliseRiscoService analise = new AnaliseRiscoService(List.of(new FatorSetorRiscoOficial(),
            new FatorProximidadeAgua(), new FatorElevacaoRelativa(), new FatorOcorrenciasLocais(), new FatorHistoricoMunicipal()));

    @Test
    @DisplayName("Consulta grava classificação, justificativa, fatores com fonte e geometrias do mapa")
    void realizaConsulta() {
        Endereco e = new Endereco();
        e.setRua("Rua de Teste");
        e.setCidade("Canoas");
        e.setEstado("RS");
        e.setLatitude(ContextoTeste.LAT);
        e.setLongitude(ContextoTeste.LON);
        e.setPrecisao(PrecisaoGeocodificacao.NUMERO);
        EnderecoRequest req = new EnderecoRequest(null, "Rua de Teste", "100", null, "Canoas", "RS", null, null);
        when(enderecoService.resolverParaAnalise(req)).thenReturn(e);
        when(coleta.coletar(e)).thenReturn(ContextoTeste.novo().rioA(120).elevacao(4.0, 3.0, 120.0, 6.0).semSetores().construir());
        FonteDados osm = new FonteDados("OSM_CURSOS_AGUA", "OpenStreetMap", TipoFonte.BASE_GEOGRAFICA, StatusIntegracao.ATIVA);
        when(fontes.listar()).thenReturn(List.of(osm));
        when(consultas.save(any(Consulta.class))).thenAnswer(inv -> inv.getArgument(0));

        ConsultaService servico = new ConsultaService(enderecoService, coleta, analise, consultas, fontes, json);
        servico.realizar(req, null);

        ArgumentCaptor<Consulta> captor = ArgumentCaptor.forClass(Consulta.class);
        verify(consultas).save(captor.capture());
        Consulta c = captor.getValue();
        assertEquals(ClassificacaoRisco.ALTO, c.getClassificacao());
        assertEquals(5, c.getFatores().size());
        assertEquals(osm, c.getFatores().stream().filter(f -> f.getCodigo().equals("PROXIMIDADE_AGUA")).findFirst().orElseThrow().getFonte());
        assertTrue(c.getJustificativa().contains("Média ponderada"));
        JsonNode mapa = json.readTree(c.getDadosMapa());
        assertEquals(1, mapa.path("cursosDagua").size());
        assertTrue(mapa.path("aguaMaisProxima").path("distanciaMetros").asInt() > 100);
    }

    @Test
    @DisplayName("Consulta inexistente gera 'não encontrado'")
    void consultaInexistente() {
        ConsultaService servico = new ConsultaService(enderecoService, coleta, analise, consultas, fontes, json);
        UUID id = UUID.randomUUID();
        when(consultas.findWithEnderecoById(id)).thenReturn(Optional.empty());
        org.junit.jupiter.api.Assertions.assertThrows(com.alertalar.exception.RecursoNaoEncontradoException.class, () -> servico.buscar(id));
    }

    @Test
    @DisplayName("Recorte de linhas mantém apenas os trechos perto do ponto")
    void recortaLinhas() {
        List<double[]> linha = List.of(new double[]{ContextoTeste.LAT, ContextoTeste.LON + 0.001},
                new double[]{ContextoTeste.LAT, ContextoTeste.LON + 0.002},
                new double[]{ContextoTeste.LAT, ContextoTeste.LON + 0.5},
                new double[]{ContextoTeste.LAT, ContextoTeste.LON + 0.6});
        List<List<double[]>> trechos = ConsultaService.recortar(linha, ContextoTeste.LAT, ContextoTeste.LON, 2000);
        assertEquals(1, trechos.size());
        assertEquals(3, trechos.get(0).size()); // dois pontos internos + o ponto de saída
    }
}
