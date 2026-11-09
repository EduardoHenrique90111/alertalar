package com.alertalar.controller;

import com.alertalar.dto.RotaSeguraResponse;
import com.alertalar.exception.IntegracaoExternaException;
import com.alertalar.exception.RegraNegocioException;
import com.alertalar.integration.valhalla.ModoRota;
import com.alertalar.service.ProgressoRota;
import com.alertalar.service.ProgressoRota.CalculoCanceladoException;
import com.alertalar.service.RotaSeguraService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Rota segura até um terreno mais alto, a partir de um ponto (localização atual ou endereço). */
@RestController
@RequestMapping("/api/rotas")
public class RotaController implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(RotaController.class);

    /**
     * Tempo máximo do cálculo acompanhado. Cada serviço externo tem até 25 s para responder e o
     * roteador pode ser chamado até 4 vezes; acima disso a pessoa recebe um aviso para tentar de novo.
     */
    static final Duration TEMPO_MAXIMO = Duration.ofMinutes(3);

    private final RotaSeguraService service;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public RotaController(RotaSeguraService service) {
        this.service = service;
    }

    @Override
    public void destroy() {
        executor.shutdownNow();
    }

    /**
     * Ex.: GET /api/rotas/segura?lat=-29.92&lon=-51.18&modo=A_PE (destino: terreno mais alto, escolhido
     * automaticamente) ou com destLat/destLon para um destino escolhido pela pessoa.
     */
    @GetMapping("/segura")
    public RotaSeguraResponse segura(@RequestParam @DecimalMin("-34.0") @DecimalMax("5.5") double lat,
                                     @RequestParam @DecimalMin("-74.2") @DecimalMax("-28.6") double lon,
                                     @RequestParam(defaultValue = "A_PE") ModoRota modo,
                                     @RequestParam(required = false) @DecimalMin("-34.0") @DecimalMax("5.5") Double destLat,
                                     @RequestParam(required = false) @DecimalMin("-74.2") @DecimalMax("-28.6") Double destLon) {
        exigirDestinoCompleto(destLat, destLon);
        return destLat == null ? service.calcular(lat, lon, modo) : service.calcularAte(lat, lon, destLat, destLon, modo);
    }

    /**
     * A mesma rota segura, com o andamento de cada consulta externa enviado à tela enquanto o caminho
     * é calculado (Server-Sent Events). Eventos:
     * <ul>
     *   <li>{@code etapa}: {@link ProgressoRota.Evento} (altura, áreas de risco, caminho, perfil);</li>
     *   <li>{@code resultado}: a {@link RotaSeguraResponse}, igual à de {@code /api/rotas/segura};</li>
     *   <li>{@code erro}: {@code {status, title, detail}}, no formato dos demais erros da API.</li>
     * </ul>
     * Parâmetros inválidos são recusados antes do andamento começar, com a resposta de erro comum.
     */
    @GetMapping(value = "/segura/progresso", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter seguraComProgresso(@RequestParam @DecimalMin("-34.0") @DecimalMax("5.5") double lat,
                                         @RequestParam @DecimalMin("-74.2") @DecimalMax("-28.6") double lon,
                                         @RequestParam(defaultValue = "A_PE") ModoRota modo,
                                         @RequestParam(required = false) @DecimalMin("-34.0") @DecimalMax("5.5") Double destLat,
                                         @RequestParam(required = false) @DecimalMin("-74.2") @DecimalMax("-28.6") Double destLon,
                                         HttpServletResponse resposta) {
        exigirDestinoCompleto(destLat, destLon);
        // Pede aos proxies (ex.: Nginx) que repassem cada evento na hora, sem juntar tudo no fim
        resposta.setHeader("X-Accel-Buffering", "no");
        if (destLat == null) {
            service.validarPartida(lat, lon);
        } else {
            service.validarDestino(lat, lon, destLat, destLon);
        }
        SseEmitter emissor = new SseEmitter(TEMPO_MAXIMO.toMillis());
        Envio envio = new Envio(emissor);
        emissor.onError(e -> envio.desistir());
        emissor.onTimeout(() -> {
            envio.erro(HttpStatus.SERVICE_UNAVAILABLE, "Cálculo demorado demais",
                    "Os serviços de mapas não responderam a tempo. Tente novamente em instantes.");
            envio.desistir();
            emissor.complete();
        });
        executor.execute(() -> {
            try {
                RotaSeguraResponse r = destLat == null
                        ? service.calcular(lat, lon, modo, envio)
                        : service.calcularAte(lat, lon, destLat, destLon, modo, envio);
                envio.enviar("resultado", r);
            } catch (CalculoCanceladoException e) {
                log.debug("Rota cancelada por quem pediu.");
            } catch (RegraNegocioException e) {
                envio.erro(HttpStatus.UNPROCESSABLE_CONTENT, "Operação não permitida", e.getMessage());
            } catch (IntegracaoExternaException e) {
                envio.erro(HttpStatus.SERVICE_UNAVAILABLE, "Serviço externo indisponível", e.getMessage());
            } catch (RuntimeException e) {
                log.error("Erro inesperado ao calcular a rota segura", e);
                envio.erro(HttpStatus.INTERNAL_SERVER_ERROR, "Erro interno",
                        "Não foi possível calcular a rota agora. Tente novamente em instantes.");
            } finally {
                envio.encerrar();
            }
        });
        return emissor;
    }

    private static void exigirDestinoCompleto(Double destLat, Double destLon) {
        if ((destLat == null) != (destLon == null)) {
            throw new IllegalArgumentException("Informe destLat e destLon juntos, ou nenhum dos dois.");
        }
    }

    /** Envia os eventos para a tela; se a pessoa sair da página, o cálculo para na próxima etapa. */
    private static final class Envio implements ProgressoRota {
        private final SseEmitter emissor;
        private final AtomicBoolean desistiu = new AtomicBoolean();
        private final AtomicBoolean encerrado = new AtomicBoolean();

        Envio(SseEmitter emissor) {
            this.emissor = emissor;
        }

        @Override
        public void informar(Evento evento) {
            enviar("etapa", evento);
        }

        @Override
        public boolean cancelado() {
            return desistiu.get();
        }

        void desistir() {
            desistiu.set(true);
        }

        void erro(HttpStatus status, String titulo, String detalhe) {
            enviar("erro", Map.of("status", status.value(), "title", titulo, "detail", detalhe));
        }

        synchronized void enviar(String nome, Object dados) {
            if (desistiu.get() || encerrado.get()) {
                return;
            }
            try {
                emissor.send(SseEmitter.event().name(nome).data(dados, MediaType.APPLICATION_JSON));
            } catch (IOException | IllegalStateException e) {
                // conexão fechada (página fechada ou cálculo cancelado)
                desistir();
            }
        }

        void encerrar() {
            if (encerrado.compareAndSet(false, true)) {
                try {
                    emissor.complete();
                } catch (IllegalStateException e) {
                    // já encerrado por tempo esgotado
                }
            }
        }
    }
}
