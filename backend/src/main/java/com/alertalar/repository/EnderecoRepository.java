package com.alertalar.repository;

import com.alertalar.entity.Endereco;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface EnderecoRepository extends JpaRepository<Endereco, Long> {

    Optional<Endereco> findByChaveNormalizada(String chaveNormalizada);
}
