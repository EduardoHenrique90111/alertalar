package com.alertalar.support;

import com.alertalar.integration.valhalla.Polyline;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Respostas no MESMO FORMATO das APIs reais (ViaCEP, Nominatim, IBGE, Overpass, Open-Meteo,
 * ArcGIS do SGB). Os valores são fictícios e existem apenas para os testes automatizados.
 */
public final class RespostasSimuladas {

    /** Ponto de teste (endereço fictício). */
    public static final double LAT = -29.92;
    public static final double LON = -51.18;

    private RespostasSimuladas() {
    }

    public static String viaCep() {
        return """
                {"cep":"92010-000","logradouro":"Rua de Teste","complemento":"","unidade":"","bairro":"Centro",
                 "localidade":"Canoas","uf":"RS","estado":"Rio Grande do Sul","regiao":"Sul","ibge":"4304606",
                 "gia":"","ddd":"51","siafi":"8589"}
                """;
    }

    public static String viaCepInexistente() {
        return "{\"erro\": \"true\"}";
    }

    public static String nominatimNumero() {
        return String.format(Locale.ROOT, """
                [{"place_id":1,"lat":"%.6f","lon":"%.6f","category":"place","type":"house","place_rank":30,
                  "addresstype":"place","name":"","display_name":"100, Rua de Teste, Centro, Canoas, Rio Grande do Sul, Brasil",
                  "address":{"house_number":"100","road":"Rua de Teste","suburb":"Centro","city":"Canoas",
                  "state":"Rio Grande do Sul","ISO3166-2-lvl4":"BR-RS","postcode":"92010-000","country":"Brasil","country_code":"br"}}]
                """, LAT, LON);
    }

    public static String nominatimApenasRua() {
        return String.format(Locale.ROOT, """
                [{"place_id":2,"lat":"%.6f","lon":"%.6f","category":"highway","type":"residential","place_rank":26,
                  "addresstype":"road","display_name":"Rua de Teste, Centro, Canoas, Rio Grande do Sul, Brasil",
                  "address":{"road":"Rua de Teste","suburb":"Centro","city":"Canoas","state":"Rio Grande do Sul",
                  "ISO3166-2-lvl4":"BR-RS","country_code":"br"}}]
                """, LAT, LON);
    }

    public static String nominatimApenasCidade() {
        return """
                [{"place_id":3,"lat":"-29.9128","lon":"-51.1857","category":"boundary","type":"administrative","place_rank":16,
                  "addresstype":"city","display_name":"Canoas, Rio Grande do Sul, Brasil",
                  "address":{"city":"Canoas","state":"Rio Grande do Sul","ISO3166-2-lvl4":"BR-RS","country_code":"br"}}]
                """;
    }

    public static String ibgeMunicipiosRs() {
        return """
                [{"id":4304606,"nome":"Canoas"},{"id":4314902,"nome":"Porto Alegre"},{"id":4318705,"nome":"São Leopoldo"}]
                """;
    }

    /** Um rio a ~110 m a leste do ponto e um córrego a ~40 m ao norte. */
    public static String overpassComRioECorrego() {
        double lonRio = LON + 0.00114;  // ~110 m
        double latCorrego = LAT + 0.00036; // ~40 m
        return String.format(Locale.ROOT, """
                {"version":0.6,"generator":"Overpass API","elements":[
                  {"type":"way","id":101,"tags":{"waterway":"river","name":"Rio dos Testes"},
                   "geometry":[{"lat":%.6f,"lon":%.6f},{"lat":%.6f,"lon":%.6f}]},
                  {"type":"way","id":102,"tags":{"waterway":"stream","name":"Arroio Pequeno"},
                   "geometry":[{"lat":%.6f,"lon":%.6f},{"lat":%.6f,"lon":%.6f}]}
                ]}
                """, LAT - 0.005, lonRio, LAT + 0.005, lonRio, latCorrego, LON - 0.003, latCorrego, LON + 0.003);
    }

    public static String overpassVazio() {
        return "{\"version\":0.6,\"elements\":[]}";
    }

    /** Elevações: ponto 4 m, entorno 9 m, água 3 m (10 valores, mesma ordem da requisição). */
    public static String elevacao(int quantidade) {
        StringBuilder sb = new StringBuilder("{\"elevation\":[4.0");
        for (int i = 1; i < quantidade; i++) {
            sb.append(i <= 8 ? ",9.0" : ",3.0");
        }
        return sb.append("]}").toString();
    }

    public static String sgbSemSetores() {
        return "{\"objectIdFieldName\":\"objectid\",\"geometryType\":\"esriGeometryPolygon\",\"features\":[]}";
    }

    /** Setor de inundação "Muito alto" contendo o ponto de teste. */
    public static String sgbSetorContendoPonto() {
        double d = 0.001;
        return String.format(Locale.ROOT, """
                {"objectIdFieldName":"objectid","geometryType":"esriGeometryPolygon","spatialReference":{"wkid":4326},
                 "features":[{"attributes":{"objectid":7,"uf":"RS","munic":"Canoas","num_setor":"RS_CANOAS_SR_01_SGB",
                   "local":"Bairro de teste","tipolo_g1":"Hidrológico","tipolo_e1":"Inundação","cobrade_01":"1.2.1.0.0",
                   "descricao":"Área de planície às margens do rio","grau_risco":"Muito alto","data_setor":1704067200000},
                  "geometry":{"rings":[[[%.6f,%.6f],[%.6f,%.6f],[%.6f,%.6f],[%.6f,%.6f],[%.6f,%.6f]]]}}]}
                """, LON - d, LAT - d, LON + d, LAT - d, LON + d, LAT + d, LON - d, LAT + d, LON - d, LAT - d);
    }

    public static String sgbErro() {
        return "{\"error\":{\"code\":400,\"message\":\"Unable to complete operation.\",\"details\":[]}}";
    }

    /** Chuva horária: 2 mm/h nas últimas 24 h e 1 mm/h depois, no fuso -03:00. */
    public static String previsaoChuva(LocalDateTime agoraUtc) {
        LocalDateTime local = agoraUtc.atOffset(ZoneOffset.UTC).withOffsetSameInstant(ZoneOffset.ofHours(-3)).toLocalDateTime();
        LocalDateTime inicio = local.withMinute(0).withSecond(0).withNano(0).minusHours(30);
        StringBuilder tempos = new StringBuilder();
        StringBuilder valores = new StringBuilder();
        DateTimeFormatter f = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");
        for (int i = 0; i < 30 + 80; i++) {
            LocalDateTime h = inicio.plusHours(i);
            if (i > 0) {
                tempos.append(',');
                valores.append(',');
            }
            tempos.append('"').append(h.format(f)).append('"');
            valores.append(h.isAfter(local) ? "1.0" : "2.0");
        }
        return "{\"latitude\":-29.92,\"longitude\":-51.18,\"utc_offset_seconds\":-10800,\"timezone\":\"America/Sao_Paulo\","
                + "\"hourly_units\":{\"precipitation\":\"mm\"},\"hourly\":{\"time\":[" + tempos + "],\"precipitation\":[" + valores + "]}}";
    }

    /** Vazão diária crescente (100 → 200 m³/s) de 7 dias atrás até 7 dias à frente. */
    public static String vazao(LocalDate hoje) {
        StringBuilder tempos = new StringBuilder();
        StringBuilder valores = new StringBuilder();
        for (int i = -7; i <= 7; i++) {
            if (i > -7) {
                tempos.append(',');
                valores.append(',');
            }
            tempos.append('"').append(hoje.plusDays(i)).append('"');
            valores.append(i <= 0 ? "100.0" : "200.0");
        }
        return "{\"latitude\":-29.92,\"longitude\":-51.18,\"daily_units\":{\"river_discharge\":\"m³/s\"},"
                + "\"daily\":{\"time\":[" + tempos + "],\"river_discharge\":[" + valores + "]}}";
    }

    /** Resposta do Valhalla (/route): trajeto em 3 pontos, com o "shape" em polyline de precisão 6. */
    public static String valhallaRota(double latO, double lonO, double latD, double lonD) {
        double latM = latO;
        double lonM = lonD;
        String shape = Polyline.codificar(List.of(new double[]{latO, lonO}, new double[]{latM, lonM},
                new double[]{latD, lonD}), 6).replace("\\", "\\\\");
        return String.format(Locale.ROOT, """
                {"trip":{"locations":[{"type":"break","lat":%1$.6f,"lon":%2$.6f},{"type":"break","lat":%3$.6f,"lon":%4$.6f}],
                 "legs":[{"maneuvers":[
                   {"type":2,"instruction":"Siga a leste na Rua de Teste.","time":120.5,"length":0.150,"begin_shape_index":0,"end_shape_index":1},
                   {"type":15,"instruction":"Vire à esquerda na Rua do Morro.","time":90.0,"length":0.110,"begin_shape_index":1,"end_shape_index":2},
                   {"type":4,"instruction":"Você chegou ao seu destino.","time":0.0,"length":0.0,"begin_shape_index":2,"end_shape_index":2}],
                  "summary":{"length":0.260,"time":210.5},"shape":"%5$s"}],
                 "summary":{"length":0.260,"time":210.5},"status_message":"Found route between points","status":0,
                 "units":"kilometers","language":"pt-BR"}}
                """, latO, lonO, latD, lonD, shape);
    }

    /** Erro do Valhalla quando não há caminho entre os pontos (HTTP 400). */
    public static String valhallaSemCaminho() {
        return "{\"error_code\":442,\"error\":\"No path could be found for input\",\"status_code\":400,\"status\":\"Bad Request\"}";
    }
}
