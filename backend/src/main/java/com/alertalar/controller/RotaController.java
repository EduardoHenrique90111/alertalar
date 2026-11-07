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

    /** Ex.: GET /api/rotas/segura?lat=-29.92&lon=-51.18&modo=A_PE */
    @GetMapping("/segura")
    public RotaSeguraResponse segura(@RequestParam @DecimalMin("-34.0") @DecimalMax("5.5") double lat,
                                     @RequestParam @DecimalMin("-74.2") @DecimalMax("-28.6") double lon,
                                     @RequestParam(defaultValue = "A_PE") ModoRota modo) {
        return service.calcular(lat, lon, modo);
    }
}
