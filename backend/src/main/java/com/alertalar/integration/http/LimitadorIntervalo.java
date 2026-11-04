package com.alertalar.integration.http;

import java.time.Duration;

/**
 * Garante um intervalo mínimo entre chamadas a uma API (ex.: Nominatim público exige
 * no máximo 1 requisição por segundo para toda a aplicação).
 */
public class LimitadorIntervalo {

    private final long intervaloNanos;
    private long ultimaChamada = 0L;

    public LimitadorIntervalo(Duration intervalo) {
        this.intervaloNanos = intervalo.toNanos();
    }

    public synchronized void aguardarVez() {
        long agora = System.nanoTime();
        long espera = ultimaChamada + intervaloNanos - agora;
        if (ultimaChamada != 0L && espera > 0) {
            try {
                Thread.sleep(Duration.ofNanos(espera));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        ultimaChamada = System.nanoTime();
    }
}
