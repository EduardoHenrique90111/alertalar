package com.alertalar.dto;

import com.alertalar.util.UnidadeFederativa;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Endereço informado pelo usuário. Latitude e longitude são opcionais: quando informadas,
 * representam a posição confirmada pelo usuário no mapa.
 */
public record EnderecoRequest(
        @Pattern(regexp = "^(\\d{5}-?\\d{3})?$", message = "O CEP deve ter 8 dígitos (ex.: 92010-000).")
        String cep,

        @NotBlank(message = "Informe a rua ou avenida.")
        @Size(max = 200, message = "A rua deve ter no máximo 200 caracteres.")
        String rua,

        @Size(max = 20, message = "O número deve ter no máximo 20 caracteres.")
        @Pattern(regexp = "^[0-9A-Za-z/ -]*$", message = "Número inválido. Use apenas números, letras, \"/\" ou \"-\".")
        String numero,

        @Size(max = 120, message = "O bairro deve ter no máximo 120 caracteres.")
        String bairro,

        @NotBlank(message = "Informe a cidade.")
        @Size(max = 120, message = "A cidade deve ter no máximo 120 caracteres.")
        String cidade,

        @NotBlank(message = "Informe o estado (UF).")
        @Pattern(regexp = UnidadeFederativa.REGEX, flags = Pattern.Flag.CASE_INSENSITIVE,
                message = "Estado inválido. Use a sigla da UF, ex.: RS.")
        String estado,

        @DecimalMin(value = "-34.0", message = "A latitude deve estar dentro do território brasileiro.")
        @DecimalMax(value = "5.5", message = "A latitude deve estar dentro do território brasileiro.")
        Double latitude,

        @DecimalMin(value = "-74.2", message = "A longitude deve estar dentro do território brasileiro.")
        @DecimalMax(value = "-28.6", message = "A longitude deve estar dentro do território brasileiro.")
        Double longitude) {

    @JsonIgnore
    @AssertTrue(message = "Informe latitude e longitude juntas, ou nenhuma das duas.")
    public boolean isCoordenadasCompletas() {
        return (latitude == null) == (longitude == null);
    }

    @JsonIgnore
    public boolean possuiCoordenadas() {
        return latitude != null && longitude != null;
    }
}
