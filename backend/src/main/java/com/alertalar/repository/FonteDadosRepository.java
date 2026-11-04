package com.alertalar.repository;

import com.alertalar.entity.FonteDados;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface FonteDadosRepository extends JpaRepository<FonteDados, Long> {

    Optional<FonteDados> findByCodigo(String codigo);

    List<FonteDados> findAllByOrderByIdAsc();
}
