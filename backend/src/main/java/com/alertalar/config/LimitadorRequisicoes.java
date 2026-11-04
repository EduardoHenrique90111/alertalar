package com.alertalar.config;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Limite de requisições por chave (ex.: IP + rota) em janela deslizante, em memória.
 * Protege as APIs públicas usadas pelo sistema e reduz abuso de formulários.
 * Para várias instâncias do servidor, substitua por um limitador distribuído (ex.: Redis).
 */
public class LimitadorRequisicoes {

    private static final int MAXIMO_CHAVES = 50_000;

    private final Map<String, Deque<Long>> janelas = new ConcurrentHashMap<>();
    private final Clock relogio;

    public LimitadorRequisicoes() {
        this(Clock.systemUTC());
    }

    public LimitadorRequisicoes(Clock relogio) {
        this.relogio = relogio;
    }

    /** @return true se a requisição pode prosseguir */
    public boolean permitir(String chave, int limite, Duration janela) {
        long agora = relogio.millis();
        long inicio = agora - janela.toMillis();
        if (janelas.size() > MAXIMO_CHAVES) {
            limpar(inicio);
        }
        Deque<Long> fila = janelas.computeIfAbsent(chave, k -> new ArrayDeque<>());
        synchronized (fila) {
            while (!fila.isEmpty() && fila.peekFirst() < inicio) {
                fila.pollFirst();
            }
            if (fila.size() >= limite) {
                return false;
            }
            fila.addLast(agora);
            return true;
        }
    }

    private void limpar(long inicio) {
        janelas.entrySet().removeIf(e -> {
            synchronized (e.getValue()) {
                Long ultimo = e.getValue().peekLast();
                return ultimo == null || ultimo < inicio;
            }
        });
    }
}
