package com.alertalar.exception;

import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Converte exceções em respostas HTTP no formato Problem Details (RFC 9457), com mensagens
 * em português e sem expor detalhes internos (stack traces, SQL, nomes de classes).
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex, HttpHeaders headers,
                                                                  HttpStatusCode status, WebRequest request) {
        Map<String, String> erros = new LinkedHashMap<>();
        for (FieldError fe : ex.getBindingResult().getFieldErrors()) {
            erros.putIfAbsent(fe.getField(), fe.getDefaultMessage());
        }
        ex.getBindingResult().getGlobalErrors().forEach(ge -> erros.putIfAbsent(ge.getObjectName(), ge.getDefaultMessage()));
        ProblemDetail pd = problema(HttpStatus.BAD_REQUEST, "Dados inválidos", "Verifique os campos destacados.");
        pd.setProperty("erros", erros);
        return ResponseEntity.badRequest().body(pd);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
                                                                            HttpHeaders headers, HttpStatusCode status,
                                                                            WebRequest request) {
        Map<String, String> erros = new LinkedHashMap<>();
        ex.getParameterValidationResults().forEach(r -> r.getResolvableErrors().forEach(e ->
                erros.putIfAbsent(r.getMethodParameter().getParameterName(), e.getDefaultMessage())));
        ProblemDetail pd = problema(HttpStatus.BAD_REQUEST, "Parâmetros inválidos", "Verifique os parâmetros enviados.");
        pd.setProperty("erros", erros);
        return ResponseEntity.badRequest().body(pd);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
                                                             HttpStatusCode statusCode, WebRequest request) {
        // Demais erros do Spring MVC (JSON malformado, método não suportado, 404...) com texto em português
        if (body instanceof ProblemDetail pd) {
            pd.setTitle(tituloPadrao(statusCode.value()));
            pd.setDetail(detalhePadrao(statusCode.value()));
        }
        return super.handleExceptionInternal(ex, body, headers, statusCode, request);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ProblemDetail> violacao(ConstraintViolationException ex) {
        Map<String, String> erros = new LinkedHashMap<>();
        ex.getConstraintViolations().forEach(v -> erros.putIfAbsent(v.getPropertyPath().toString(), v.getMessage()));
        ProblemDetail pd = problema(HttpStatus.BAD_REQUEST, "Dados inválidos", "Verifique os campos enviados.");
        pd.setProperty("erros", erros);
        return ResponseEntity.badRequest().body(pd);
    }

    @ExceptionHandler(RecursoNaoEncontradoException.class)
    public ResponseEntity<ProblemDetail> naoEncontrado(RecursoNaoEncontradoException ex) {
        return resposta(HttpStatus.NOT_FOUND, "Não encontrado", ex.getMessage());
    }

    @ExceptionHandler(EnderecoNaoEncontradoException.class)
    public ResponseEntity<ProblemDetail> enderecoNaoEncontrado(EnderecoNaoEncontradoException ex) {
        return resposta(HttpStatus.NOT_FOUND, "Endereço não encontrado", ex.getMessage());
    }

    @ExceptionHandler(PrecisaoInsuficienteException.class)
    public ResponseEntity<ProblemDetail> precisaoInsuficiente(PrecisaoInsuficienteException ex) {
        ProblemDetail pd = problema(HttpStatus.UNPROCESSABLE_CONTENT, "Precisão insuficiente", ex.getMessage());
        pd.setProperty("codigo", "PRECISAO_INSUFICIENTE");
        pd.setProperty("latitudeAproximada", ex.getLatitudeAproximada());
        pd.setProperty("longitudeAproximada", ex.getLongitudeAproximada());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT).body(pd);
    }

    @ExceptionHandler(RegraNegocioException.class)
    public ResponseEntity<ProblemDetail> regraNegocio(RegraNegocioException ex) {
        return resposta(HttpStatus.UNPROCESSABLE_CONTENT, "Operação não permitida", ex.getMessage());
    }

    @ExceptionHandler(ArquivoInvalidoException.class)
    public ResponseEntity<ProblemDetail> arquivoInvalido(ArquivoInvalidoException ex) {
        return resposta(HttpStatus.BAD_REQUEST, "Arquivo inválido", ex.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ProblemDetail> argumentoInvalido(IllegalArgumentException ex) {
        return resposta(HttpStatus.BAD_REQUEST, "Requisição inválida",
                ex.getMessage() == null ? "Parâmetros inválidos." : ex.getMessage());
    }

    @ExceptionHandler(IntegracaoExternaException.class)
    public ResponseEntity<ProblemDetail> integracao(IntegracaoExternaException ex) {
        ProblemDetail pd = problema(HttpStatus.SERVICE_UNAVAILABLE, "Serviço externo indisponível", ex.getMessage());
        pd.setProperty("fonte", ex.getFonte());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(pd);
    }

    @ExceptionHandler(DisabledException.class)
    public ResponseEntity<ProblemDetail> contaDesativada(DisabledException ex) {
        return resposta(HttpStatus.UNAUTHORIZED, "Conta desativada", "Esta conta está desativada.");
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ProblemDetail> autenticacao(AuthenticationException ex) {
        return resposta(HttpStatus.UNAUTHORIZED, "Falha na autenticação", "E-mail ou senha incorretos.");
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ProblemDetail> acessoNegado(AccessDeniedException ex) {
        return resposta(HttpStatus.FORBIDDEN, "Acesso negado", "Você não tem permissão para esta operação.");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> inesperado(Exception ex) {
        String codigo = UUID.randomUUID().toString().substring(0, 8);
        log.error("Erro inesperado [{}]", codigo, ex);
        ProblemDetail pd = problema(HttpStatus.INTERNAL_SERVER_ERROR, "Erro interno",
                "Ocorreu um erro inesperado. Tente novamente em instantes. Código para suporte: " + codigo);
        return ResponseEntity.internalServerError().body(pd);
    }

    private static ResponseEntity<ProblemDetail> resposta(HttpStatus status, String titulo, String detalhe) {
        return ResponseEntity.status(status).body(problema(status, titulo, detalhe));
    }

    private static ProblemDetail problema(HttpStatus status, String titulo, String detalhe) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detalhe);
        pd.setTitle(titulo);
        return pd;
    }

    private static String tituloPadrao(int status) {
        return switch (status) {
            case 400 -> "Requisição inválida";
            case 404 -> "Não encontrado";
            case 405 -> "Método não permitido";
            case 406 -> "Formato não aceito";
            case 413 -> "Conteúdo muito grande";
            case 415 -> "Tipo de conteúdo não suportado";
            default -> status >= 500 ? "Erro interno" : "Erro na requisição";
        };
    }

    private static String detalhePadrao(int status) {
        return switch (status) {
            case 400 -> "A requisição está malformada ou contém valores inválidos.";
            case 404 -> "O recurso solicitado não existe.";
            case 405 -> "Este método HTTP não é aceito neste endereço.";
            case 413 -> "O arquivo excede o tamanho máximo de 25 MB.";
            case 415 -> "Envie os dados no formato JSON.";
            default -> status >= 500 ? "Ocorreu um erro inesperado." : "Não foi possível processar a requisição.";
        };
    }
}
