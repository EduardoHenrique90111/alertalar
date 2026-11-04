package com.alertalar.controller;

import com.alertalar.dto.ConsultaResponse;
import com.alertalar.dto.EnderecoRequest;
import com.alertalar.service.ConsultaService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/consultas")
public class ConsultaController {

    private final ConsultaService service;
    private final UsuarioAtual usuarioAtual;

    public ConsultaController(ConsultaService service, UsuarioAtual usuarioAtual) {
        this.service = service;
        this.usuarioAtual = usuarioAtual;
    }

    /** Realiza a consulta de risco para um endereço e grava o resultado. */
    @PostMapping
    public ResponseEntity<ConsultaResponse> consultar(@Valid @RequestBody EnderecoRequest endereco) {
        UUID id = service.realizar(endereco, usuarioAtual.obter().orElse(null));
        return ResponseEntity.created(URI.create("/api/consultas/" + id)).body(service.buscar(id));
    }

    @GetMapping("/{id}")
    public ConsultaResponse buscar(@PathVariable UUID id) {
        return service.buscar(id);
    }

    /** Histórico de consultas do usuário logado. */
    @GetMapping("/minhas")
    public List<ConsultaResponse.Resumo> minhas() {
        return service.doUsuario(usuarioAtual.obrigatorio().getId());
    }
}
