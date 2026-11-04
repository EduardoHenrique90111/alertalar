package com.alertalar.service;

import com.alertalar.entity.FonteDados;
import com.alertalar.repository.FonteDadosRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Leitura das fontes de dados e registro da data do último acesso/atualização. */
@Service
public class FonteDadosService {

    private static final Logger log = LoggerFactory.getLogger(FonteDadosService.class);
    /** Evita gravar no banco a cada consulta: atualiza no máximo a cada 10 minutos por fonte. */
    private static final Duration INTERVALO_GRAVACAO = Duration.ofMinutes(10);

    private final FonteDadosRepository repository;
    private final Map<String, Instant> ultimaGravacao = new ConcurrentHashMap<>();

    public FonteDadosService(FonteDadosRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<FonteDados> listar() {
        return repository.findAllByOrderByIdAsc();
    }

    @Transactional(readOnly = true)
    public Optional<FonteDados> buscar(String codigo) {
        return repository.findByCodigo(codigo);
    }

    public FonteDados obrigatoria(String codigo) {
        return repository.findByCodigo(codigo)
                .orElseThrow(() -> new IllegalStateException("Fonte de dados não cadastrada: " + codigo));
    }

    /** Registra que a fonte respondeu com sucesso agora (data exibida como "última atualização"). */
    @Transactional
    public void registrarAcesso(String codigo, Instant quando) {
        Instant anterior = ultimaGravacao.get(codigo);
        if (anterior != null && Duration.between(anterior, quando).compareTo(INTERVALO_GRAVACAO) < 0) {
            return;
        }
        try {
            repository.findByCodigo(codigo).ifPresent(f -> {
                f.setDataAtualizacao(quando);
                repository.save(f);
            });
            ultimaGravacao.put(codigo, quando);
        } catch (RuntimeException e) {
            log.warn("Não foi possível registrar o acesso à fonte {}", codigo, e);
        }
    }
}
