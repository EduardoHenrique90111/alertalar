package com.alertalar.service.analise;

import com.alertalar.integration.overpass.OverpassClient.CursoDagua;
import com.alertalar.util.Geo;

import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/** Ponto mais próximo de um curso/corpo d'água em relação ao endereço consultado. */
public record ProximidadeAgua(CursoDagua curso, double latitude, double longitude, double distanciaMetros) {

    public static Optional<ProximidadeAgua> maisProximo(double lat, double lon, List<CursoDagua> cursos,
                                                        Predicate<CursoDagua> filtro) {
        ProximidadeAgua melhor = null;
        for (CursoDagua c : cursos) {
            if (!filtro.test(c)) {
                continue;
            }
            for (List<double[]> parte : c.partes()) {
                if (c.area() && parte.size() >= 4 && Geo.dentroDoPoligono(lat, lon, parte)) {
                    return Optional.of(new ProximidadeAgua(c, lat, lon, 0));
                }
                double[] p = Geo.pontoMaisProximo(lat, lon, parte);
                if (p != null && (melhor == null || p[2] < melhor.distanciaMetros())) {
                    melhor = new ProximidadeAgua(c, p[0], p[1], p[2]);
                }
            }
        }
        return Optional.ofNullable(melhor);
    }

    public static Optional<ProximidadeAgua> maisProximo(double lat, double lon, List<CursoDagua> cursos) {
        return maisProximo(lat, lon, cursos, c -> true);
    }
}
