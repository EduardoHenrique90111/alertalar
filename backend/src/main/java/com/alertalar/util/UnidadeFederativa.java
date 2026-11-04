package com.alertalar.util;

import java.util.Arrays;
import java.util.Optional;

/** As 27 unidades federativas do Brasil. */
public enum UnidadeFederativa {
    AC("Acre"), AL("Alagoas"), AP("Amapá"), AM("Amazonas"), BA("Bahia"), CE("Ceará"),
    DF("Distrito Federal"), ES("Espírito Santo"), GO("Goiás"), MA("Maranhão"), MT("Mato Grosso"),
    MS("Mato Grosso do Sul"), MG("Minas Gerais"), PA("Pará"), PB("Paraíba"), PR("Paraná"),
    PE("Pernambuco"), PI("Piauí"), RJ("Rio de Janeiro"), RN("Rio Grande do Norte"),
    RS("Rio Grande do Sul"), RO("Rondônia"), RR("Roraima"), SC("Santa Catarina"),
    SP("São Paulo"), SE("Sergipe"), TO("Tocantins");

    /** Expressão regular usada na validação dos DTOs. */
    public static final String REGEX =
            "AC|AL|AP|AM|BA|CE|DF|ES|GO|MA|MT|MS|MG|PA|PB|PR|PE|PI|RJ|RN|RS|RO|RR|SC|SP|SE|TO";

    private final String nome;

    UnidadeFederativa(String nome) {
        this.nome = nome;
    }

    public String getNome() {
        return nome;
    }

    public static Optional<UnidadeFederativa> daSigla(String sigla) {
        if (sigla == null) {
            return Optional.empty();
        }
        String s = sigla.trim().toUpperCase();
        return Arrays.stream(values()).filter(uf -> uf.name().equals(s)).findFirst();
    }

    /** Aceita sigla ("RS") ou nome ("Rio Grande do Sul", com ou sem acento). */
    public static Optional<UnidadeFederativa> deTexto(String texto) {
        Optional<UnidadeFederativa> porSigla = daSigla(texto);
        if (porSigla.isPresent()) {
            return porSigla;
        }
        String n = Texto.normalizar(texto);
        return Arrays.stream(values()).filter(uf -> Texto.normalizar(uf.nome).equals(n)).findFirst();
    }
}
