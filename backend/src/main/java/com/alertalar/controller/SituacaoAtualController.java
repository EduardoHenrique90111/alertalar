package com.alertalar.controller;

import com.alertalar.dto.SituacaoAtualResponse;
import com.alertalar.service.SituacaoAtualService;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Chuva e vazão modeladas para o ponto e o risco deste momento (não altera a classificação da consulta). */
@RestController
@RequestMapping("/api/situacao-atual")
public class SituacaoAtualController {

    private final SituacaoAtualService service;

    public SituacaoAtualController(SituacaoAtualService service) {
        this.service = service;
    }

    @GetMapping
    public SituacaoAtualResponse consultar(@RequestParam @DecimalMin("-34.0") @DecimalMax("5.5") double lat,
                                           @RequestParam @DecimalMin("-74.2") @DecimalMax("-28.6") double lon,
                                           @RequestParam(required = false) UUID consulta) {
        return service.consultar(lat, lon, consulta);
    }
}
