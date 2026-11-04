package com.alertalar.dto;

import com.alertalar.entity.enums.TipoEvento;
import com.alertalar.util.UnidadeFederativa;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/** Relato de alagamento enviado por um usuário (entra como PENDENTE de validação). */
public record OcorrenciaRequest(
        @NotBlank(message = "Informe o local da ocorrência.")
        @Size(min = 5, max = 300, message = "O local deve ter entre 5 e 300 caracteres.")
        String localizacao,

        @NotBlank(message = "Informe o município.")
        @Size(max = 120, message = "O município deve ter no máximo 120 caracteres.")
        String municipio,

        @NotBlank(message = "Informe o estado (UF).")
        @Pattern(regexp = UnidadeFederativa.REGEX, flags = Pattern.Flag.CASE_INSENSITIVE,
                message = "Estado inválido. Use a sigla da UF, ex.: RS.")
        String estado,

        @NotNull(message = "Informe a data da ocorrência.")
        @PastOrPresent(message = "A data da ocorrência não pode estar no futuro.")
        LocalDate dataOcorrencia,

        @NotBlank(message = "Descreva o que aconteceu.")
        @Size(min = 10, max = 2000, message = "A descrição deve ter entre 10 e 2000 caracteres.")
        String descricao,

        @NotNull(message = "Informe o tipo de evento.")
        TipoEvento tipoEvento,

        @DecimalMin(value = "-34.0", message = "A latitude deve estar dentro do território brasileiro.")
        @DecimalMax(value = "5.5", message = "A latitude deve estar dentro do território brasileiro.")
        Double latitude,

        @DecimalMin(value = "-74.2", message = "A longitude deve estar dentro do território brasileiro.")
        @DecimalMax(value = "-28.6", message = "A longitude deve estar dentro do território brasileiro.")
        Double longitude,

        @Size(max = 120, message = "O nome deve ter no máximo 120 caracteres.")
        String nomeRelator,

        @Email(message = "E-mail de contato inválido.")
        @Size(max = 254, message = "O e-mail deve ter no máximo 254 caracteres.")
        String contatoRelator,

        @NotNull(message = "Confirme que as informações são verdadeiras.")
        @AssertTrue(message = "Confirme que as informações são verdadeiras.")
        Boolean declaracaoVeracidade) {

    @JsonIgnore
    @AssertTrue(message = "Informe latitude e longitude juntas, ou nenhuma das duas.")
    public boolean isCoordenadasCompletas() {
        return (latitude == null) == (longitude == null);
    }

    @JsonIgnore
    @AssertTrue(message = "A data da ocorrência deve ser posterior a 1900.")
    public boolean isDataPlausivel() {
        return dataOcorrencia == null || dataOcorrencia.getYear() >= 1900;
    }
}
