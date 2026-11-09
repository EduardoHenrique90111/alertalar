package com.alertalar.controller;

import com.alertalar.dto.ImportacaoResultado;
import com.alertalar.dto.OcorrenciaAdminResponse;
import com.alertalar.dto.PaginaResponse;
import com.alertalar.dto.ValidacaoRequest;
import com.alertalar.entity.enums.StatusValidacao;
import com.alertalar.exception.ArquivoInvalidoException;
import com.alertalar.service.ImportacaoService;
import com.alertalar.service.OcorrenciaService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

/** Moderação de relatos e importação de dados oficiais. Restrito ao papel ADMIN (ver SecurityConfig). */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final OcorrenciaService ocorrencias;
    private final ImportacaoService importacao;
    private final UsuarioAtual usuarioAtual;

    public AdminController(OcorrenciaService ocorrencias, ImportacaoService importacao, UsuarioAtual usuarioAtual) {
        this.ocorrencias = ocorrencias;
        this.importacao = importacao;
        this.usuarioAtual = usuarioAtual;
    }

    @GetMapping("/resumo")
    public Map<String, Long> resumo() {
        return Map.of("relatosPendentes", ocorrencias.contarPendentes());
    }

    @GetMapping("/ocorrencias")
    public PaginaResponse<OcorrenciaAdminResponse> listar(
            @RequestParam(defaultValue = "PENDENTE") StatusValidacao status,
            @RequestParam(required = false) @Size(max = 100) String busca,
            @RequestParam(defaultValue = "0") @Min(0) int pagina,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int tamanho) {
        return ocorrencias.listarParaModeracao(status, busca, pagina, tamanho);
    }

    @PatchMapping("/ocorrencias/{id}/validacao")
    public OcorrenciaAdminResponse validar(@PathVariable Long id, @Valid @RequestBody ValidacaoRequest req) {
        return ocorrencias.validar(id, req, usuarioAtual.obrigatorio());
    }

    /** Importa a planilha CSV de registros oficiais exportada do S2iD / Atlas Digital. */
    @PostMapping(value = "/importacoes/registros-oficiais", consumes = "multipart/form-data")
    public ImportacaoResultado importar(@RequestPart("arquivo") MultipartFile arquivo) throws IOException {
        if (arquivo.isEmpty()) {
            throw new ArquivoInvalidoException("Selecione um arquivo CSV.");
        }
        String nome = arquivo.getOriginalFilename() == null ? "planilha.csv" : arquivo.getOriginalFilename();
        if (!nome.toLowerCase().endsWith(".csv") && !nome.toLowerCase().endsWith(".txt")) {
            throw new ArquivoInvalidoException("Envie a planilha no formato CSV (no Excel: Arquivo > Salvar como > CSV UTF-8).");
        }
        return importacao.importarRegistrosOficiais(arquivo.getBytes(), nome, usuarioAtual.obrigatorio());
    }
}
