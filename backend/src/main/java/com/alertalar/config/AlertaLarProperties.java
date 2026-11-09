package com.alertalar.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;

/**
 * Configurações do AlertaLar (prefixo "alertalar" no application.properties).
 * Credenciais e URLs podem ser sobrescritas por variáveis de ambiente.
 */
@ConfigurationProperties(prefix = "alertalar")
public record AlertaLarProperties(
        @DefaultValue("") String contatoEmail,
        @DefaultValue("AlertaLar/1.0") String nomeAplicacao,
        @DefaultValue("") String urlPublica,
        @DefaultValue Admin admin,
        @DefaultValue Cors cors,
        @DefaultValue Integracoes integracoes,
        @DefaultValue Analise analise,
        @DefaultValue Limites limites) {

    /**
     * User-Agent identificável, exigido pelas políticas de uso do Nominatim e do OSM:
     * "AlertaLar/1.0 (+https://alertalar.onrender.com; contato@...)". O endereço do site vem de
     * ALERTALAR_URL_PUBLICA ou, no Render, da variável RENDER_EXTERNAL_URL que ele mesmo define.
     */
    public String userAgent() {
        List<String> partes = new java.util.ArrayList<>();
        if (urlPublica != null && !urlPublica.isBlank()) {
            partes.add("+" + urlPublica.trim());
        }
        if (contatoEmail != null && !contatoEmail.isBlank()) {
            partes.add(contatoEmail.trim());
        }
        return partes.isEmpty() ? nomeAplicacao : nomeAplicacao + " (" + String.join("; ", partes) + ")";
    }

    public record Admin(@DefaultValue("Administrador") String nome,
                        @DefaultValue("") String email,
                        @DefaultValue("") String senha) {
    }

    public record Cors(@DefaultValue List<String> origensPermitidas) {
    }

    public record Integracoes(
            @DefaultValue("5s") Duration timeoutConexao,
            @DefaultValue("25s") Duration timeoutLeitura,
            @DefaultValue("https://viacep.com.br/ws") String viacepUrl,
            @DefaultValue("https://nominatim.openstreetmap.org") String nominatimUrl,
            @DefaultValue("1100ms") Duration nominatimIntervaloMinimo,
            @DefaultValue("https://servicodados.ibge.gov.br/api/v1/localidades") String ibgeUrl,
            @DefaultValue("https://overpass-api.de/api/interpreter") String overpassUrl,
            @DefaultValue("https://api.open-meteo.com/v1/elevation") String elevacaoUrl,
            @DefaultValue("https://api.open-meteo.com/v1/forecast") String previsaoUrl,
            @DefaultValue("https://flood-api.open-meteo.com/v1/flood") String vazaoUrl,
            @DefaultValue("https://geoportal.sgb.gov.br/server/rest/services/gestaoterritorial/risco/MapServer/0")
            String sgbSetoresRiscoUrl,
            @DefaultValue("https://valhalla1.openstreetmap.de") String rotasUrl,
            @DefaultValue("") String rotasChaveApi,
            @DefaultValue("1100ms") Duration rotasIntervaloMinimo,
            @DefaultValue("https://photon.komoot.io") String photonUrl,
            @DefaultValue("1100ms") Duration photonIntervaloMinimo) {
    }

    public record Analise(@DefaultValue("1500") int raioCursosAguaMetros,
                          @DefaultValue("500") int raioOcorrenciasMetros,
                          @DefaultValue("300") int raioSetoresRiscoMetros,
                          @DefaultValue("30") int anosHistorico) {
    }

    public record Limites(@DefaultValue("15") int consultasPorMinuto,
                          @DefaultValue("10") int relatosPorHora,
                          @DefaultValue("10") int autenticacaoPorMinuto,
                          @DefaultValue("10") int rotasPorMinuto) {
    }
}
