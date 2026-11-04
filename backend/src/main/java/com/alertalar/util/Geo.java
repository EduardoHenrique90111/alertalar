package com.alertalar.util;

import java.util.List;

/**
 * Cálculos geográficos simples (sem dependências externas). Para distâncias de até alguns
 * quilômetros usa-se uma projeção equiretangular local, com erro desprezível para esta escala.
 */
public final class Geo {

    public static final double RAIO_TERRA_METROS = 6_371_008.8;

    /** Limites aproximados do território brasileiro (inclui ilhas oceânicas). */
    public static final double LAT_MIN_BRASIL = -34.0;
    public static final double LAT_MAX_BRASIL = 5.5;
    public static final double LON_MIN_BRASIL = -74.2;
    public static final double LON_MAX_BRASIL = -28.6;

    private Geo() {
    }

    public static boolean dentroDoBrasil(double lat, double lon) {
        return lat >= LAT_MIN_BRASIL && lat <= LAT_MAX_BRASIL && lon >= LON_MIN_BRASIL && lon <= LON_MAX_BRASIL;
    }

    /** Distância em metros entre dois pontos (fórmula de Haversine). */
    public static double distanciaMetros(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * RAIO_TERRA_METROS * Math.asin(Math.min(1.0, Math.sqrt(a)));
    }

    /**
     * Ponto mais próximo de uma polilinha (lista de [lat, lon]) em relação a um ponto de referência.
     * @return [lat, lon, distanciaMetros] ou null se a linha estiver vazia
     */
    public static double[] pontoMaisProximo(double lat, double lon, List<double[]> linha) {
        if (linha == null || linha.isEmpty()) {
            return null;
        }
        double cosLat = Math.cos(Math.toRadians(lat));
        double melhorDist = Double.MAX_VALUE;
        double[] melhor = null;
        if (linha.size() == 1) {
            double[] p = linha.get(0);
            return new double[]{p[0], p[1], distanciaMetros(lat, lon, p[0], p[1])};
        }
        for (int i = 0; i < linha.size() - 1; i++) {
            double[] a = linha.get(i);
            double[] b = linha.get(i + 1);
            // coordenadas locais em metros, origem no ponto de referência
            double ax = Math.toRadians(a[1] - lon) * cosLat * RAIO_TERRA_METROS;
            double ay = Math.toRadians(a[0] - lat) * RAIO_TERRA_METROS;
            double bx = Math.toRadians(b[1] - lon) * cosLat * RAIO_TERRA_METROS;
            double by = Math.toRadians(b[0] - lat) * RAIO_TERRA_METROS;
            double dx = bx - ax;
            double dy = by - ay;
            double comp2 = dx * dx + dy * dy;
            double t = comp2 == 0 ? 0 : Math.max(0, Math.min(1, -(ax * dx + ay * dy) / comp2));
            double px = ax + t * dx;
            double py = ay + t * dy;
            double d = Math.hypot(px, py);
            if (d < melhorDist) {
                melhorDist = d;
                double pLat = lat + Math.toDegrees(py / RAIO_TERRA_METROS);
                double pLon = lon + Math.toDegrees(px / (RAIO_TERRA_METROS * cosLat));
                melhor = new double[]{pLat, pLon, d};
            }
        }
        return melhor;
    }

    /** Teste ponto-em-polígono (ray casting). O anel é uma lista de [lat, lon]. */
    public static boolean dentroDoPoligono(double lat, double lon, List<double[]> anel) {
        if (anel == null || anel.size() < 3) {
            return false;
        }
        boolean dentro = false;
        for (int i = 0, j = anel.size() - 1; i < anel.size(); j = i++) {
            double yi = anel.get(i)[0];
            double xi = anel.get(i)[1];
            double yj = anel.get(j)[0];
            double xj = anel.get(j)[1];
            boolean cruza = ((yi > lat) != (yj > lat)) && (lon < (xj - xi) * (lat - yi) / (yj - yi) + xi);
            if (cruza) {
                dentro = !dentro;
            }
        }
        return dentro;
    }

    /** Ponto deslocado a partir de uma origem, dada a distância (m) e o rumo (graus a partir do norte). */
    public static double[] deslocar(double lat, double lon, double distanciaMetros, double rumoGraus) {
        double d = distanciaMetros / RAIO_TERRA_METROS;
        double rumo = Math.toRadians(rumoGraus);
        double lat1 = Math.toRadians(lat);
        double lon1 = Math.toRadians(lon);
        double lat2 = Math.asin(Math.sin(lat1) * Math.cos(d) + Math.cos(lat1) * Math.sin(d) * Math.cos(rumo));
        double lon2 = lon1 + Math.atan2(Math.sin(rumo) * Math.sin(d) * Math.cos(lat1), Math.cos(d) - Math.sin(lat1) * Math.sin(lat2));
        return new double[]{Math.toDegrees(lat2), Math.toDegrees(lon2)};
    }

    /** Retângulo envolvente [latMin, latMax, lonMin, lonMax] de um círculo de raio em metros. */
    public static double[] retanguloEnvolvente(double lat, double lon, double raioMetros) {
        double dLat = Math.toDegrees(raioMetros / RAIO_TERRA_METROS);
        double dLon = Math.toDegrees(raioMetros / (RAIO_TERRA_METROS * Math.cos(Math.toRadians(lat))));
        return new double[]{lat - dLat, lat + dLat, lon - dLon, lon + dLon};
    }
}
