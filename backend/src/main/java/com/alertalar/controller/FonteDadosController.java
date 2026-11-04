package com.alertalar.controller;

import com.alertalar.dto.FonteResponse;
import com.alertalar.service.FonteDadosService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Fontes de dados, com a situação real de cada integração e a data da última atualização. */
@RestController
@RequestMapping("/api/fontes")
public class FonteDadosController {

    private final FonteDadosService service;

    public FonteDadosController(FonteDadosService service) {
        this.service = service;
    }

    @GetMapping
    public List<FonteResponse> listar() {
        return service.listar().stream().map(FonteResponse::de).toList();
    }
}
