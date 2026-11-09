package com.alertalar.service;

import com.alertalar.dto.EnderecoRequest;
import com.alertalar.exception.IntegracaoExternaException;
import com.alertalar.integration.nominatim.NominatimClient;
import com.alertalar.integration.nominatim.NominatimClient.ResultadoGeocodificacao;
import com.alertalar.integration.photon.PhotonClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Reserva da geocodificação: Nominatim recusando pedidos (limite por IP) e o Photon assumindo. */
@ExtendWith(MockitoExtension.class)
class GeocodificacaoServiceTest {

    @Mock NominatimClient nominatim;
    @Mock PhotonClient photon;
    @Mock FonteDadosService fontes;

    private final Relogio relogio = new Relogio();
    private GeocodificacaoService servico;
    private final UnaryOperator<List<ResultadoGeocodificacao>> semFiltro = l -> l;

    @BeforeEach
    void criar() {
        servico = new GeocodificacaoService(nominatim, photon, fontes, relogio);
    }

    /** Relógio que o teste avança. */
    private static final class Relogio extends Clock {
        Instant agora = Instant.parse("2026-11-09T12:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return agora;
        }
    }

    private static EnderecoRequest req(String numero) {
        return new EnderecoRequest(null, "Rua de Teste", numero, null, "Canoas", "RS", null, null);
    }

    private static ResultadoGeocodificacao casa(String numero, String rua) {
        return new ResultadoGeocodificacao(-29.92, -51.18, 30, "house", numero, rua, "Centro", "Canoas", "RS", null,
                numero + ", " + rua + ", Centro, Canoas, Rio Grande do Sul, Brasil");
    }

    private static IntegracaoExternaException recusa(HttpStatus status, String retryAfter) {
        HttpHeaders cabecalhos = new HttpHeaders();
        if (retryAfter != null) {
            cabecalhos.add("Retry-After", retryAfter);
        }
        return new IntegracaoExternaException(NominatimClient.FONTE, "A fonte Nominatim (OpenStreetMap) não atendeu a solicitação.",
                HttpClientErrorException.create(status, status.getReasonPhrase(), cabecalhos, new byte[0], null));
    }

    @Test
    @DisplayName("Nominatim no limite (HTTP 429): a busca segue no Photon e ele fica 5 min em pausa")
    void limiteDoNominatim() {
        when(nominatim.buscarEstruturado(anyString(), any(), anyString(), anyString(), anyInt()))
                .thenThrow(recusa(HttpStatus.TOO_MANY_REQUESTS, null));
        when(photon.buscar(anyString(), anyInt())).thenAnswer(inv -> {
            String q = inv.getArgument(0);
            return List.of(casa(q.contains("200") ? "200" : "100", "Rua de Teste"));
        });

        GeocodificacaoService.Busca busca = servico.buscar(req("100"), semFiltro);

        assertEquals(PhotonClient.FONTE, busca.fonte());
        assertEquals("100", busca.resultados().get(0).numero());
        verify(photon).buscar(eq("Rua de Teste 100, Canoas, Rio Grande do Sul"), anyInt());
        verify(fontes).registrarAcesso(eq("PHOTON"), any());
        assertTrue(servico.nominatimEmPausa());

        // Em pausa: o próximo endereço vai direto ao Photon, sem insistir no Nominatim
        assertEquals(PhotonClient.FONTE, servico.buscar(req("200"), semFiltro).fonte());
        verify(nominatim, times(1)).buscarEstruturado(anyString(), any(), anyString(), anyString(), anyInt());

        // Passados 5 minutos, o Nominatim volta a ser o primeiro
        relogio.agora = relogio.agora.plus(Duration.ofMinutes(5)).plusSeconds(1);
        assertFalse(servico.nominatimEmPausa());
        servico.buscar(req("300"), semFiltro);
        verify(nominatim, times(2)).buscarEstruturado(anyString(), any(), anyString(), anyString(), anyInt());
    }

    @Test
    @DisplayName("Tempo de pausa: o pedido pela fonte (Retry-After), bloqueio e falhas passageiras")
    void tempoDePausa() {
        assertEquals(Duration.ofMinutes(5), GeocodificacaoService.pausa(recusa(HttpStatus.TOO_MANY_REQUESTS, null)));
        assertEquals(Duration.ofSeconds(120), GeocodificacaoService.pausa(recusa(HttpStatus.TOO_MANY_REQUESTS, "120")));
        assertEquals(Duration.ofMinutes(1), GeocodificacaoService.pausa(recusa(HttpStatus.TOO_MANY_REQUESTS, "5")), "mínimo de 1 min");
        assertEquals(Duration.ofMinutes(30), GeocodificacaoService.pausa(recusa(HttpStatus.TOO_MANY_REQUESTS, "86400")), "máximo de 30 min");
        assertEquals(Duration.ofMinutes(30), GeocodificacaoService.pausa(recusa(HttpStatus.FORBIDDEN, null)));
        assertEquals(Duration.ofMinutes(1), GeocodificacaoService.pausa(new IntegracaoExternaException("N", "erro",
                HttpServerErrorException.create(HttpStatus.BAD_GATEWAY, "Bad Gateway", new HttpHeaders(), new byte[0], null))));
        assertEquals(Duration.ofMinutes(1), GeocodificacaoService.pausa(new IntegracaoExternaException("N", "sem resposta",
                new ResourceAccessException("timeout"))));
    }

    @Test
    @DisplayName("Os dois fora do ar: mensagem clara, com a opção de marcar no mapa")
    void ambosForaDoAr() {
        when(nominatim.buscarEstruturado(anyString(), any(), anyString(), anyString(), anyInt()))
                .thenThrow(recusa(HttpStatus.TOO_MANY_REQUESTS, null));
        when(photon.buscar(anyString(), anyInt())).thenThrow(new IntegracaoExternaException(PhotonClient.FONTE, "fora do ar"));

        IntegracaoExternaException e = assertThrows(IntegracaoExternaException.class, () -> servico.buscar(req("100"), semFiltro));

        assertEquals(GeocodificacaoService.MENSAGEM_INDISPONIVEL, e.getMessage());
        assertTrue(e.getMessage().contains("marque a posição direto no mapa"));
    }

    @Test
    @DisplayName("Em pausa, se o Photon também falhar, o Nominatim ainda é tentado como última opção")
    void emPausaPhotonFalha() {
        when(nominatim.buscarEstruturado(anyString(), any(), anyString(), anyString(), anyInt()))
                .thenThrow(recusa(HttpStatus.TOO_MANY_REQUESTS, null))
                .thenReturn(List.of(casa("200", "Rua de Teste")));
        when(photon.buscar(anyString(), anyInt()))
                .thenReturn(List.of(casa("100", "Rua de Teste")))
                .thenThrow(new IntegracaoExternaException(PhotonClient.FONTE, "fora do ar"));
        servico.buscar(req("100"), semFiltro); // põe o Nominatim em pausa

        GeocodificacaoService.Busca busca = servico.buscar(req("200"), semFiltro);

        assertEquals(NominatimClient.FONTE, busca.fonte());
        assertFalse(servico.nominatimEmPausa(), "respondeu de novo: sai da pausa");
    }

    @Test
    @DisplayName("Nominatim não acha: o Photon tenta; nome diferente do digitado vale só como posição aproximada")
    void nominatimNaoAcha() {
        when(photon.buscar(anyString(), anyInt())).thenReturn(List.of(casa("100", "Rua das Palmeiras")));

        GeocodificacaoService.Busca busca = servico.buscar(req("100"), semFiltro);

        assertEquals(PhotonClient.FONTE, busca.fonte());
        assertEquals(20, busca.resultados().get(0).placeRank(), "outra rua: só posição aproximada");
        assertFalse(servico.nominatimEmPausa(), "não achar não é falha");
        verify(nominatim).buscarTexto(anyString(), anyInt()); // as tentativas do Nominatim continuam iguais
    }

    @Test
    @DisplayName("Nominatim não acha e o Photon cai: resposta de endereço não encontrado (não é erro de serviço)")
    void naoEncontrado() {
        when(photon.buscar(anyString(), anyInt())).thenThrow(new IntegracaoExternaException(PhotonClient.FONTE, "fora do ar"));

        GeocodificacaoService.Busca busca = servico.buscar(req("100"), semFiltro);

        assertTrue(busca.resultados().isEmpty());
        assertEquals(NominatimClient.FONTE, busca.fonte());
    }

    @Test
    @DisplayName("Endereço encontrado fica em memória: a mesma busca não consulta os serviços de novo")
    void cache() {
        when(nominatim.buscarEstruturado(anyString(), any(), anyString(), anyString(), anyInt()))
                .thenReturn(List.of(casa("100", "Rua de Teste")));

        servico.buscar(req("100"), semFiltro);
        GeocodificacaoService.Busca deNovo = servico.buscar(new EnderecoRequest(null, " rua de teste ", "100", null, "CANOAS", "rs",
                null, null), semFiltro);

        assertEquals(NominatimClient.FONTE, deNovo.fonte());
        verify(nominatim, times(1)).buscarEstruturado(anyString(), any(), anyString(), anyString(), anyInt());
        verify(photon, never()).buscar(anyString(), anyInt());
    }

    @Test
    @DisplayName("Conferência do nome no resultado aproximado do Photon")
    void conferenciaDoNome() {
        assertTrue(GeocodificacaoService.nomeConfere("Av. Guilherme Schell", "6750, Avenida Guilherme Schell, Centro, Canoas"));
        assertTrue(GeocodificacaoService.nomeConfere("Park Shopping", "ParkShopping Canoas, 4502, Avenida Farroupilha"));
        assertTrue(GeocodificacaoService.nomeConfere("Rua São José", "Rua Sao Jose, Centro"));
        assertFalse(GeocodificacaoService.nomeConfere("Rua das Flores", "Rua das Palmeiras, Centro"));
        ResultadoGeocodificacao rua = new ResultadoGeocodificacao(-29.9, -51.1, 26, "residential", null, "Rua das Flores", null,
                "Canoas", "RS", null, "Rua das Flores, Canoas");
        assertEquals(26, GeocodificacaoService.conferirNome(rua, "R. das Flores").placeRank());
    }
}
