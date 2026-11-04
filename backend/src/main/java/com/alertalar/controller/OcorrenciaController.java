package com.alertalar.controller;

import com.alertalar.dto.OcorrenciaRequest;
import com.alertalar.dto.OcorrenciaResponse;
import com.alertalar.dto.PaginaResponse;
import com.alertalar.entity.enums.OrigemOcorrencia;
import com.alertalar.service.OcorrenciaService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/ocorrencias")
public class OcorrenciaController {

    private final OcorrenciaService service;
    private final UsuarioAtual usuarioAtual;

    public OcorrenciaController(OcorrenciaService service, UsuarioAtual usuarioAtual) {
        this.service = service;
        this.usuarioAtual = usuarioAtual;
    }

    /** Ocorrências confirmadas (oficiais e relatos validados), com filtros opcionais. */
    @GetMapping
    public PaginaResponse<OcorrenciaResponse> listar(
            @RequestParam(required = false) @Pattern(regexp = "^[A-Za-z]{2}$", message = "UF inválida") String estado,
            @RequestParam(required = false) @Size(max = 120) String municipio,
            @RequestParam(required = false) OrigemOcorrencia origem,
            @RequestParam(defaultValue = "0") @Min(0) int pagina,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int tamanho) {
        return service.listarConfirmadas(estado, municipio, origem, pagina, tamanho);
    }

    /** Ocorrências confirmadas com coordenadas em torno de um ponto (para o mapa). */
    @GetMapping("/proximas")
    public List<OcorrenciaResponse> proximas(
            @RequestParam @DecimalMin("-34.0") @DecimalMax("5.5") double lat,
            @RequestParam @DecimalMin("-74.2") @DecimalMax("-28.6") double lon,
            @RequestParam(defaultValue = "1000") @Min(50) @Max(10000) int raio,
            @RequestParam(defaultValue = "30") @Min(1) @Max(100) int anos) {
        return service.confirmadasProximas(lat, lon, raio, anos);
    }

    /** Envia um relato de alagamento. Ele fica PENDENTE até ser validado pela moderação. */
    @PostMapping
    public ResponseEntity<OcorrenciaResponse> registrar(@Valid @RequestBody OcorrenciaRequest req) {
        OcorrenciaResponse criada = service.registrarRelato(req, usuarioAtual.obter().orElse(null));
        return ResponseEntity.status(HttpStatus.CREATED).body(criada);
    }
}
