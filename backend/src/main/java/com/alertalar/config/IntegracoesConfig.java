package com.alertalar.config;

import com.alertalar.integration.http.ClienteHttpExterno;
import com.alertalar.integration.http.LimitadorIntervalo;
import com.alertalar.integration.ibge.IbgeLocalidadesClient;
import com.alertalar.integration.nominatim.NominatimClient;
import com.alertalar.integration.openmeteo.ClimaClient;
import com.alertalar.integration.openmeteo.ElevacaoClient;
import com.alertalar.integration.overpass.OverpassClient;
import com.alertalar.integration.s2id.LeitorRegistrosOficiaisCsv;
import com.alertalar.integration.sgb.SgbSetoresRiscoClient;
import com.alertalar.integration.viacep.ViaCepClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.net.ProxySelector;
import java.net.http.HttpClient;

/** Cria o cliente HTTP compartilhado e os clientes de cada fonte externa. */
@Configuration
public class IntegracoesConfig {

    @Bean
    public ClienteHttpExterno clienteHttpExterno(AlertaLarProperties props, JsonMapper jsonMapper) {
        AlertaLarProperties.Integracoes cfg = props.integracoes();
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(cfg.timeoutConexao())
                .followRedirects(HttpClient.Redirect.NORMAL)
                .proxy(ProxySelector.getDefault())
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        JdkClientHttpRequestFactory fabrica = new JdkClientHttpRequestFactory(httpClient);
        fabrica.setReadTimeout(cfg.timeoutLeitura());
        RestClient restClient = RestClient.builder()
                .requestFactory(fabrica)
                .defaultHeader(HttpHeaders.USER_AGENT, props.userAgent())
                .defaultHeader(HttpHeaders.ACCEPT_LANGUAGE, "pt-BR,pt;q=0.9")
                .build();
        return new ClienteHttpExterno(restClient, jsonMapper);
    }

    @Bean
    public ViaCepClient viaCepClient(ClienteHttpExterno http, AlertaLarProperties props) {
        return new ViaCepClient(http, props.integracoes().viacepUrl());
    }

    @Bean
    public NominatimClient nominatimClient(ClienteHttpExterno http, AlertaLarProperties props) {
        return new NominatimClient(http, props.integracoes().nominatimUrl(), props.contatoEmail(),
                new LimitadorIntervalo(props.integracoes().nominatimIntervaloMinimo()));
    }

    @Bean
    public IbgeLocalidadesClient ibgeLocalidadesClient(ClienteHttpExterno http, AlertaLarProperties props) {
        return new IbgeLocalidadesClient(http, props.integracoes().ibgeUrl());
    }

    @Bean
    public OverpassClient overpassClient(ClienteHttpExterno http, AlertaLarProperties props) {
        return new OverpassClient(http, props.integracoes().overpassUrl());
    }

    @Bean
    public ElevacaoClient elevacaoClient(ClienteHttpExterno http, AlertaLarProperties props) {
        return new ElevacaoClient(http, props.integracoes().elevacaoUrl());
    }

    @Bean
    public ClimaClient climaClient(ClienteHttpExterno http, AlertaLarProperties props) {
        return new ClimaClient(http, props.integracoes().previsaoUrl(), props.integracoes().vazaoUrl());
    }

    @Bean
    public SgbSetoresRiscoClient sgbSetoresRiscoClient(ClienteHttpExterno http, AlertaLarProperties props) {
        return new SgbSetoresRiscoClient(http, props.integracoes().sgbSetoresRiscoUrl());
    }

    @Bean
    public LeitorRegistrosOficiaisCsv leitorRegistrosOficiaisCsv() {
        return new LeitorRegistrosOficiaisCsv();
    }
}
