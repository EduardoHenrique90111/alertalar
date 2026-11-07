package com.alertalar.service;

import com.alertalar.dto.EnderecoRequest;
import com.alertalar.dto.LocalizacaoResponse;
import com.alertalar.entity.Endereco;
import com.alertalar.entity.enums.PrecisaoGeocodificacao;
import com.alertalar.exception.EnderecoNaoEncontradoException;
import com.alertalar.exception.IntegracaoExternaException;
import com.alertalar.exception.PrecisaoInsuficienteException;
import com.alertalar.integration.ibge.IbgeLocalidadesClient;
import com.alertalar.integration.ibge.MunicipiosIbge;
import com.alertalar.integration.nominatim.NominatimClient;
import com.alertalar.integration.nominatim.NominatimClient.ResultadoGeocodificacao;
import com.alertalar.integration.viacep.ViaCepClient;
import com.alertalar.integration.viacep.ViaCepClient.DadosCep;
import com.alertalar.repository.EnderecoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Geocodificação e validação de endereços (APIs externas simuladas com Mockito). */
@ExtendWith(MockitoExtension.class)
class EnderecoServiceTest {

    @Mock ViaCepClient viaCep;
    @Mock NominatimClient nominatim;
    @Mock IbgeLocalidadesClient ibge;
    @Mock EnderecoRepository repository;
    @Mock FonteDadosService fontes;

    EnderecoService servico;

    @BeforeEach
    void preparar() {
        servico = new EnderecoService(viaCep, nominatim, ibge, MunicipiosIbge.vazio(), repository, fontes);
        lenient().when(repository.save(any(Endereco.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(repository.findByChaveNormalizada(anyString())).thenReturn(Optional.empty());
    }

    private static EnderecoRequest req(String numero) {
        return new EnderecoRequest(null, "Rua de Teste", numero, "Centro", "Canoas", "RS", null, null);
    }

    private static ResultadoGeocodificacao resultado(int rank, String numero, String cidade, String uf) {
        return new ResultadoGeocodificacao(-29.92, -51.18, rank, "road", numero, "Rua de Teste", "Centro", cidade, uf, null,
                "Rua de Teste, Canoas");
    }

    @Test
    @DisplayName("Número encontrado pelo Nominatim gera precisão NUMERO e código IBGE")
    void geocodificaComNumero() {
        when(nominatim.buscarEstruturado("Rua de Teste", "100", "Canoas", "RS", 5))
                .thenReturn(List.of(resultado(30, "100", "Canoas", "RS")));
        when(ibge.codigoMunicipio("RS", "Canoas")).thenReturn(Optional.of("4304606"));

        Endereco e = servico.resolverParaAnalise(req("100"));

        assertEquals(PrecisaoGeocodificacao.NUMERO, e.getPrecisao());
        assertEquals("4304606", e.getCodigoIbge());
        assertEquals(-29.92, e.getLatitude());
        assertNotNull(e.getChaveNormalizada());
    }

    @Test
    @DisplayName("Somente a rua encontrada gera precisão RUA")
    void geocodificaSoRua() {
        when(nominatim.buscarEstruturado(anyString(), any(), anyString(), anyString(), anyInt()))
                .thenReturn(List.of(resultado(26, null, "Canoas", "RS")));
        assertEquals(PrecisaoGeocodificacao.RUA, servico.resolverParaAnalise(req("100")).getPrecisao());
    }

    @Test
    @DisplayName("Resultado só no nível de cidade NÃO é analisado (precisão insuficiente)")
    void recusaPrecisaoDeCidade() {
        when(nominatim.buscarEstruturado(anyString(), any(), anyString(), anyString(), anyInt()))
                .thenReturn(List.of(resultado(16, null, "Canoas", "RS")));

        PrecisaoInsuficienteException ex = assertThrows(PrecisaoInsuficienteException.class, () -> servico.resolverParaAnalise(req("100")));
        assertEquals(-29.92, ex.getLatitudeAproximada());
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("Resultado em outra cidade/UF é descartado e o endereço é tratado como não encontrado")
    void descartaOutraCidade() {
        when(nominatim.buscarEstruturado(anyString(), any(), anyString(), anyString(), anyInt()))
                .thenReturn(List.of(resultado(30, "100", "Curitiba", "PR")));
        when(nominatim.buscarTexto(anyString(), anyInt())).thenReturn(List.of());
        assertThrows(EnderecoNaoEncontradoException.class, () -> servico.resolverParaAnalise(req("100")));
    }

    @Test
    @DisplayName("Coordenadas confirmadas no mapa dispensam a geocodificação")
    void usaCoordenadasDoUsuario() {
        when(ibge.codigoMunicipio("RS", "Canoas")).thenReturn(Optional.of("4304606"));
        EnderecoRequest r = new EnderecoRequest(null, "Rua de Teste", "100", null, "Canoas", "RS", -29.95, -51.17);

        Endereco e = servico.resolverParaAnalise(r);

        assertEquals(PrecisaoGeocodificacao.MANUAL, e.getPrecisao());
        assertEquals(-29.95, e.getLatitude());
        assertNull(e.getChaveNormalizada());
        verify(nominatim, never()).buscarEstruturado(anyString(), any(), anyString(), anyString(), anyInt());
    }

    @Test
    @DisplayName("Endereço já geocodificado é reaproveitado do banco (cache)")
    void usaCache() {
        Endereco existente = new Endereco();
        existente.setPrecisao(PrecisaoGeocodificacao.NUMERO);
        when(repository.findByChaveNormalizada("rua de teste|100|canoas|RS")).thenReturn(Optional.of(existente));
        assertEquals(existente, servico.resolverParaAnalise(req("100")));
        verify(nominatim, never()).buscarEstruturado(anyString(), any(), anyString(), anyString(), anyInt());
    }

    @Test
    @DisplayName("Falha do IBGE não impede a análise (código fica vazio)")
    void ibgeForaDoAr() {
        when(nominatim.buscarEstruturado(anyString(), any(), anyString(), anyString(), anyInt()))
                .thenReturn(List.of(resultado(30, "100", "Canoas", "RS")));
        when(ibge.codigoMunicipio("RS", "Canoas")).thenThrow(new IntegracaoExternaException("IBGE", "fora do ar"));
        assertNull(servico.resolverParaAnalise(req("100")).getCodigoIbge());
    }

    @Test
    @DisplayName("Nome de lugar escrito separado ('Park Shopping') também é buscado junto ('ParkShopping')")
    void nomeDeLugarJunto() {
        when(nominatim.buscarEstruturado(anyString(), any(), anyString(), anyString(), anyInt())).thenReturn(List.of());
        when(nominatim.buscarTexto("Park Shopping, Canoas - RS, Brasil", 5)).thenReturn(List.of());
        when(nominatim.buscarTexto("ParkShopping, Canoas - RS, Brasil", 5)).thenReturn(List.of(
                new ResultadoGeocodificacao(-29.9105, -51.164, 30, "shop", "4502", "Avenida Farroupilha", "Marechal Rondon",
                        "Canoas", "RS", null, "ParkShopping Canoas, 4502, Avenida Farroupilha, Canoas")));
        LocalizacaoResponse r = servico.localizar(new EnderecoRequest(null, "Park Shopping", null, null, "Canoas", "RS", null, null));
        assertTrue(r.candidatos().get(0).enderecoEncontrado().startsWith("ParkShopping Canoas"));
        assertNull(EnderecoService.nomeJunto(new EnderecoRequest(null, "Rua de Teste", null, null, "Canoas", "RS", null, null)));
        assertNull(EnderecoService.nomeJunto(new EnderecoRequest(null, "Shopping", null, null, "Canoas", "RS", null, null)));
        assertNull(EnderecoService.nomeJunto(new EnderecoRequest(null, "Park Shopping", "10", null, "Canoas", "RS", null, null)));
    }

    @Test
    @DisplayName("Código IBGE sai da lista guardada no sistema mesmo com a API do IBGE fora do ar")
    void ibgePelaListaLocal() {
        EnderecoService comLista = new EnderecoService(viaCep, nominatim, ibge,
                MunicipiosIbge.de(List.<String[]>of(new String[]{"4304606", "RS", "Canoas"},
                        new String[]{"4206702", "SC", "Herval D`Oeste"})),
                repository, fontes);
        EnderecoRequest manual = new EnderecoRequest(null, "Rua de Teste", "100", null, "canoas", "RS", -29.92, -51.18);
        assertEquals("4304606", comLista.resolverParaAnalise(manual).getCodigoIbge());
        EnderecoRequest outraGrafia = new EnderecoRequest(null, "Rua A", "1", null, "Herval d'Oeste", "SC", -27.19, -51.49);
        assertEquals("4206702", comLista.resolverParaAnalise(outraGrafia).getCodigoIbge());
        verify(ibge, never()).codigoMunicipio(anyString(), anyString());
    }

    @Test
    @DisplayName("Endereço guardado sem código IBGE é completado na consulta seguinte")
    void completaCodigoDoCache() {
        Endereco existente = new Endereco();
        existente.setPrecisao(PrecisaoGeocodificacao.NUMERO);
        when(repository.findByChaveNormalizada("rua de teste|100|canoas|RS")).thenReturn(Optional.of(existente));
        when(ibge.codigoMunicipio("RS", "Canoas")).thenReturn(Optional.of("4304606"));
        assertEquals("4304606", servico.resolverParaAnalise(req("100")).getCodigoIbge());
        verify(repository).save(existente);
    }

    @Test
    @DisplayName("Código IBGE vem do ViaCEP quando o CEP confere com a cidade informada")
    void ibgePeloCep() {
        when(viaCep.buscar("92010-000")).thenReturn(Optional.of(new DadosCep("92010-000", "Rua de Teste", "Centro", "Canoas", "RS", "4304606")));
        when(nominatim.buscarEstruturado(anyString(), any(), anyString(), anyString(), anyInt()))
                .thenReturn(List.of(resultado(30, "100", "Canoas", "RS")));
        EnderecoRequest r = new EnderecoRequest("92010000", "Rua de Teste", "100", null, "Canoas", "RS", null, null);

        Endereco e = servico.resolverParaAnalise(r);

        assertEquals("4304606", e.getCodigoIbge());
        assertEquals("92010-000", e.getCep());
        assertEquals("Centro", e.getBairro());
        verify(ibge, never()).codigoMunicipio(anyString(), anyString());
    }

    @Test
    @DisplayName("Localizar devolve candidatos e indica quando a precisão não basta")
    void localizar() {
        when(nominatim.buscarEstruturado(anyString(), any(), anyString(), anyString(), anyInt()))
                .thenReturn(List.of(resultado(18, null, "Canoas", "RS")));
        LocalizacaoResponse r = servico.localizar(req(null));
        assertFalse(r.candidatos().get(0).suficienteParaAnalise());
        assertTrue(r.mensagem().contains("Clique no mapa"));
    }

    @Test
    @DisplayName("CEP inexistente gera erro explicativo")
    void cepInexistente() {
        when(viaCep.buscar("99999999")).thenReturn(Optional.empty());
        EnderecoNaoEncontradoException ex = assertThrows(EnderecoNaoEncontradoException.class, () -> servico.buscarCep("99999-999"));
        assertTrue(ex.getMessage().contains("99999-999"));
    }
}
