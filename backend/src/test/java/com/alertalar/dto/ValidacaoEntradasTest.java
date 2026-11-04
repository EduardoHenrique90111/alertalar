package com.alertalar.dto;

import com.alertalar.dto.AuthDtos.RegistroRequest;
import com.alertalar.entity.enums.TipoEvento;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDate;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Validação de endereços, relatos e cadastro (Jakarta Bean Validation). */
class ValidacaoEntradasTest {

    private static ValidatorFactory fabrica;
    private static Validator validador;

    @BeforeAll
    static void iniciar() {
        fabrica = Validation.buildDefaultValidatorFactory();
        validador = fabrica.getValidator();
    }

    @AfterAll
    static void encerrar() {
        fabrica.close();
    }

    private static EnderecoRequest endereco(String cep, String rua, String cidade, String uf, Double lat, Double lon) {
        return new EnderecoRequest(cep, rua, "100", "Centro", cidade, uf, lat, lon);
    }

    private static Set<String> camposInvalidos(Object obj) {
        return validador.validate(obj).stream().map(v -> v.getPropertyPath().toString()).collect(Collectors.toSet());
    }

    @Test
    @DisplayName("Endereço completo e válido não gera erros")
    void enderecoValido() {
        assertTrue(validador.validate(endereco("92010-000", "Rua A", "Canoas", "RS", null, null)).isEmpty());
        assertTrue(validador.validate(endereco(null, "Rua A", "Canoas", "RS", -29.9, -51.1)).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"123", "9201-0000", "abcde-fgh", "920100000"})
    @DisplayName("CEP com formato inválido é rejeitado")
    void cepInvalido(String cep) {
        assertTrue(camposInvalidos(endereco(cep, "Rua A", "Canoas", "RS", null, null)).contains("cep"));
    }

    @Test
    @DisplayName("Rua, cidade e UF são obrigatórias; UF precisa existir")
    void obrigatorios() {
        Set<String> campos = camposInvalidos(endereco(null, " ", "", "XX", null, null));
        assertTrue(campos.containsAll(Set.of("rua", "cidade", "estado")), campos.toString());
    }

    @Test
    @DisplayName("Coordenadas fora do Brasil ou incompletas são rejeitadas")
    void coordenadas() {
        assertTrue(camposInvalidos(endereco(null, "Rua A", "Canoas", "RS", 40.7, -74.0)).contains("latitude"));
        assertTrue(camposInvalidos(endereco(null, "Rua A", "Canoas", "RS", -29.9, null)).contains("coordenadasCompletas"));
    }

    @Test
    @DisplayName("Número aceita apenas caracteres seguros")
    void numeroSeguro() {
        EnderecoRequest r = new EnderecoRequest(null, "Rua A", "<script>", null, "Canoas", "RS", null, null);
        assertTrue(camposInvalidos(r).contains("numero"));
    }

    @Test
    @DisplayName("Relato exige data no passado, descrição mínima e declaração de veracidade")
    void relato() {
        OcorrenciaRequest r = new OcorrenciaRequest("Rua A, 10", "Canoas", "RS", LocalDate.now().plusDays(2), "curta",
                TipoEvento.ALAGAMENTO, null, null, null, "email-invalido", false);
        Set<String> campos = camposInvalidos(r);
        assertTrue(campos.containsAll(Set.of("dataOcorrencia", "descricao", "contatoRelator", "declaracaoVeracidade")), campos.toString());
    }

    @Test
    @DisplayName("Senha precisa ter letras e números e no mínimo 8 caracteres")
    void senha() {
        Set<ConstraintViolation<RegistroRequest>> v = validador.validate(new RegistroRequest("Ana", "ana@exemplo.com", "abcdefgh"));
        assertEquals(1, v.size());
        assertTrue(validador.validate(new RegistroRequest("Ana", "ana@exemplo.com", "abc12345")).isEmpty());
    }
}
