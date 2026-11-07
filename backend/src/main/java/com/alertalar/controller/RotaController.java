package com.alertalar.controller;

import com.alertalar.dto.RotaSeguraResponse;
import com.alertalar.integration.valhalla.ModoRota;
import com.alertalar.service.RotaSeguraService;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Rota segura até um terreno mais alto, a partir de um ponto (localização atual ou endereço). */
@RestController
@RequestMapping("/api/rotas")
public class RotaController {

    private final RotaSeguraService service;

    public RotaController(RotaSeguraService service) {
        this.service = service;
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
        if ((destLat == null) != (destLon == null)) {
            throw new IllegalArgumentException("Informe destLat e destLon juntos, ou nenhum dos dois.");
        }
        return destLat == null ? service.calcular(lat, lon, modo) : service.calcularAte(lat, lon, destLat, destLon, modo);
    }
}
