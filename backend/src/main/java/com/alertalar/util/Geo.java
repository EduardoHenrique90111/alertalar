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

    /** Rumo inicial (graus a partir do norte, 0–360) de um ponto a outro. */
    public static double rumo(double lat1, double lon1, double lat2, double lon2) {
        double f1 = Math.toRadians(lat1);
        double f2 = Math.toRadians(lat2);
        double dl = Math.toRadians(lon2 - lon1);
        double y = Math.sin(dl) * Math.cos(f2);
        double x = Math.cos(f1) * Math.sin(f2) - Math.sin(f1) * Math.cos(f2) * Math.cos(dl);
        return (Math.toDegrees(Math.atan2(y, x)) + 360) % 360;
    }

    /** "norte", "nordeste", "leste"... para um rumo em graus. */
    public static String direcao(double rumoGraus) {
        String[] nomes = {"norte", "nordeste", "leste", "sudeste", "sul", "sudoeste", "oeste", "noroeste"};
        return nomes[(int) Math.round(((rumoGraus % 360) + 360) % 360 / 45.0) % 8];
    }

    /** Comprimento (m) de uma linha de pontos [lat, lon]. */
    public static double comprimento(List<double[]> linha) {
        double total = 0;
        for (int i = 1; i < linha.size(); i++) {
            total += distanciaMetros(linha.get(i - 1)[0], linha.get(i - 1)[1], linha.get(i)[0], linha.get(i)[1]);
        }
        return total;
    }

    /**
     * Pontos igualmente espaçados ao longo de uma linha (inclui o primeiro e o último).
     * @return lista de [lat, lon, distanciaDesdeOInicio]
     */
    public static List<double[]> amostrar(List<double[]> linha, int quantidade) {
        List<double[]> resultado = new java.util.ArrayList<>();
        if (linha == null || linha.isEmpty()) {
            return resultado;
        }
        double total = comprimento(linha);
        if (quantidade < 2 || total == 0) {
            resultado.add(new double[]{linha.get(0)[0], linha.get(0)[1], 0});
            return resultado;
        }
        double passo = total / (quantidade - 1);
        double acumulado = 0;
        int i = 1;
        for (int k = 0; k < quantidade; k++) {
            double alvo = Math.min(k * passo, total);
            while (i < linha.size() - 1) {
                double seg = distanciaMetros(linha.get(i - 1)[0], linha.get(i - 1)[1], linha.get(i)[0], linha.get(i)[1]);
                if (acumulado + seg >= alvo) {
                    break;
                }
                acumulado += seg;
                i++;
            }
            double[] a = linha.get(i - 1);
            double[] b = linha.get(i);
            double seg = distanciaMetros(a[0], a[1], b[0], b[1]);
            double t = seg == 0 ? 0 : Math.max(0, Math.min(1, (alvo - acumulado) / seg));
            resultado.add(new double[]{a[0] + t * (b[0] - a[0]), a[1] + t * (b[1] - a[1]), alvo});
        }
        return resultado;
    }

    /** Retângulo envolvente [latMin, latMax, lonMin, lonMax] de um círculo de raio em metros. */
    public static double[] retanguloEnvolvente(double lat, double lon, double raioMetros) {
        double dLat = Math.toDegrees(raioMetros / RAIO_TERRA_METROS);
        double dLon = Math.toDegrees(raioMetros / (RAIO_TERRA_METROS * Math.cos(Math.toRadians(lat))));
        return new double[]{lat - dLat, lat + dLat, lon - dLon, lon + dLon};
    }
}
