package com.alertalar.controller;

import com.alertalar.dto.CepResponse;
import com.alertalar.dto.EnderecoRequest;
import com.alertalar.dto.LocalizacaoResponse;
import com.alertalar.service.EnderecoService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/enderecos")
public class EnderecoController {

    private final EnderecoService service;

    public EnderecoController(EnderecoService service) {
        this.service = service;
    }

    /** Consulta de CEP (ViaCEP) para preencher o formulário. Ex.: GET /api/enderecos/cep/92010000 */
    @GetMapping("/cep/{cep}")
    public CepResponse cep(@PathVariable
                           @Pattern(regexp = "^\\d{5}-?\\d{3}$", message = "O CEP deve ter 8 dígitos.") String cep) {
        return service.buscarCep(cep);
    }

    /**
     * Valida e geocodifica um endereço, devolvendo os candidatos para confirmação no mapa.
     * Ex.: GET /api/enderecos/buscar?rua=Rua X&numero=100&cidade=Canoas&estado=RS
     */
    @GetMapping("/buscar")
    public LocalizacaoResponse buscar(@Valid @ModelAttribute EnderecoRequest endereco) {
        return service.localizar(endereco);
    }
}
