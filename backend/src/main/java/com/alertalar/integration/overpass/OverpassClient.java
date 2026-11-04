package com.alertalar.integration.overpass;

import com.alertalar.integration.http.ClienteHttpExterno;
import com.alertalar.integration.http.JsonUtil;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Consulta rios, córregos, canais e corpos d'água do OpenStreetMap próximos a um ponto,
 * usando a Overpass API (https://wiki.openstreetmap.org/wiki/Overpass_API).
 * Os dados retornados são geometrias reais do OSM; nada é estimado ou inventado.
 */
public class OverpassClient {

    public static final String FONTE = "OpenStreetMap (Overpass API)";

    /** Cursos d'água de maior porte (canal principal de drenagem). */
    private static final Set<String> PRINCIPAIS = Set.of("river", "canal", "tidal_channel", "water", "riverbank");

    private final ClienteHttpExterno http;
    private final URI url;

    public OverpassClient(ClienteHttpExterno http, String url) {
        this.http = http;
        this.url = URI.create(url);
    }

    public List<CursoDagua> buscarProximos(double latitude, double longitude, int raioMetros) {
        String around = String.format(Locale.ROOT, "(around:%d,%.6f,%.6f)", raioMetros, latitude, longitude);
        String consulta = "[out:json][timeout:25];("
                + "way[\"waterway\"~\"^(river|stream|canal|drain|ditch|tidal_channel)$\"]" + around + ";"
                + "way[\"natural\"=\"water\"]" + around + ";"
                + "relation[\"natural\"=\"water\"]" + around + ";"
                + ");out tags geom qt;";
        JsonNode json = http.postFormularioJson(FONTE, url, Map.of("data", consulta));
        return interpretar(json);
    }

    static List<CursoDagua> interpretar(JsonNode json) {
        List<CursoDagua> lista = new ArrayList<>();
        for (JsonNode el : json.path("elements").values()) {
            String tipoElemento = JsonUtil.texto(el, "type");
            JsonNode tags = el.path("tags");
            String waterway = JsonUtil.texto(tags, "waterway");
            String natural = JsonUtil.texto(tags, "natural");
            String tipo = waterway != null ? waterway : ("water".equals(natural) ? "water" : null);
            if (tipo == null) {
                continue;
            }
            String nome = JsonUtil.texto(tags, "name");
            String subtipo = JsonUtil.texto(tags, "water");
            long id = el.path("id").asLong(0);
            boolean principal = PRINCIPAIS.contains(tipo) || "river".equals(subtipo) || "canal".equals(subtipo);
            boolean area = !"way".equals(tipoElemento) || waterway == null;
            if ("way".equals(tipoElemento)) {
                List<double[]> pontos = lerGeometria(el.path("geometry"));
                if (!pontos.isEmpty()) {
                    lista.add(new CursoDagua(id, tipo, subtipo, nome, principal, area, List.of(pontos)));
                }
            } else if ("relation".equals(tipoElemento)) {
                List<List<double[]>> partes = new ArrayList<>();
                for (JsonNode m : el.path("members").values()) {
                    List<double[]> pontos = lerGeometria(m.path("geometry"));
                    if (!pontos.isEmpty()) {
                        partes.add(pontos);
                    }
                }
                if (!partes.isEmpty()) {
                    lista.add(new CursoDagua(id, tipo, subtipo, nome, principal, true, partes));
                }
            }
        }
        return lista;
    }

    private static List<double[]> lerGeometria(JsonNode geometria) {
        List<double[]> pontos = new ArrayList<>();
        for (JsonNode p : geometria.values()) {
            Double lat = JsonUtil.numero(p, "lat");
            Double lon = JsonUtil.numero(p, "lon");
            if (lat != null && lon != null) {
                pontos.add(new double[]{lat, lon});
            }
        }
        return pontos;
    }

    /**
     * Curso ou corpo d'água do OSM.
     * @param tipo     valor da tag waterway (river, stream, canal, drain, ditch) ou "water" para corpos d'água
     * @param subtipo  valor da tag water (river, lake, reservoir...), quando houver
     * @param principal true para rios, canais e corpos d'água; false para córregos, valas e drenos
     * @param partes    uma ou mais linhas de coordenadas [lat, lon]
     */
    public record CursoDagua(long osmId, String tipo, String subtipo, String nome, boolean principal, boolean area,
                             List<List<double[]>> partes) {

        public String descricao() {
            String rotulo = switch (tipo) {
                case "river" -> "rio";
                case "stream" -> "córrego/arroio";
                case "canal" -> "canal";
                case "drain" -> "canal de drenagem";
                case "ditch" -> "vala";
                case "tidal_channel" -> "canal de maré";
                default -> subtipo == null ? "corpo d'água" : switch (subtipo) {
                    case "river" -> "rio (margens)";
                    case "lake" -> "lago";
                    case "reservoir" -> "reservatório";
                    case "pond" -> "lagoa/açude";
                    case "lagoon" -> "laguna";
                    default -> "corpo d'água";
                };
            };
            return nome == null ? rotulo : rotulo + " " + nome;
        }
    }
}
