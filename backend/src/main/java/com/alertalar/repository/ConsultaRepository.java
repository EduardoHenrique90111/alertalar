package com.alertalar.repository;

import com.alertalar.entity.Consulta;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ConsultaRepository extends JpaRepository<Consulta, UUID> {

    @EntityGraph(attributePaths = {"endereco"})
    Optional<Consulta> findWithEnderecoById(UUID id);

    @EntityGraph(attributePaths = {"fatores"})
    Optional<Consulta> findWithFatoresById(UUID id);

    @EntityGraph(attributePaths = {"endereco"})
    List<Consulta> findByUsuarioIdOrderByDataConsultaDesc(Long usuarioId, Pageable pageable);
}
