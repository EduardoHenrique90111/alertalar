package com.alertalar.integration.http;

import java.time.Clock;
import java.time.Duration;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Cache em memória com tempo de expiração e tamanho máximo. Reduz chamadas repetidas
 * às APIs públicas (respeitando suas políticas de uso) sem dependências extras.
 */
public class CacheTemporario<K, V> {

    private record Entrada<V>(V valor, long expiraEm, long criadoEm) {
    }

    private final Map<K, Entrada<V>> mapa = new ConcurrentHashMap<>();
    private final long ttlMillis;
    private final int tamanhoMaximo;
    private final Clock relogio;

    public CacheTemporario(Duration ttl, int tamanhoMaximo) {
        this(ttl, tamanhoMaximo, Clock.systemUTC());
    }

    public CacheTemporario(Duration ttl, int tamanhoMaximo, Clock relogio) {
        this.ttlMillis = ttl.toMillis();
        this.tamanhoMaximo = tamanhoMaximo;
        this.relogio = relogio;
    }

    public Optional<V> obter(K chave) {
        Entrada<V> e = mapa.get(chave);
        if (e == null) {
            return Optional.empty();
        }
        if (e.expiraEm() < relogio.millis()) {
            mapa.remove(chave, e);
            return Optional.empty();
        }
        return Optional.of(e.valor());
    }

    public void guardar(K chave, V valor) {
        if (valor == null) {
            return;
        }
        long agora = relogio.millis();
        if (mapa.size() >= tamanhoMaximo) {
            removerExpiradosOuMaisAntigo(agora);
        }
        mapa.put(chave, new Entrada<>(valor, agora + ttlMillis, agora));
    }

    /** Retorna o valor em cache ou calcula, guarda e retorna. Exceções não são guardadas. */
    public V obterOuCalcular(K chave, Supplier<V> calculo) {
        Optional<V> existente = obter(chave);
        if (existente.isPresent()) {
            return existente.get();
        }
        V valor = calculo.get();
        guardar(chave, valor);
        return valor;
    }

    public int tamanho() {
        return mapa.size();
    }

    private void removerExpiradosOuMaisAntigo(long agora) {
        mapa.entrySet().removeIf(e -> e.getValue().expiraEm() < agora);
        if (mapa.size() >= tamanhoMaximo) {
            mapa.entrySet().stream()
                    .min(Comparator.comparingLong(e -> e.getValue().criadoEm()))
                    .ifPresent(e -> mapa.remove(e.getKey()));
        }
    }
}
