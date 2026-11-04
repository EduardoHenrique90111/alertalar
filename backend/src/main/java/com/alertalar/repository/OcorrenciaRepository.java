package com.alertalar.repository;

import com.alertalar.entity.FonteDados;
import com.alertalar.entity.Ocorrencia;
import com.alertalar.entity.enums.NivelLocalizacao;
import com.alertalar.entity.enums.OrigemOcorrencia;
import com.alertalar.entity.enums.StatusValidacao;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface OcorrenciaRepository extends JpaRepository<Ocorrencia, Long> {

    /**
     * Ocorrências com coordenadas dentro de um retângulo (pré-filtro); a distância exata
     * é calculada depois em Java. Consultas parametrizadas: sem risco de SQL Injection.
     */
    @EntityGraph(attributePaths = {"fonte"})
    @Query("""
            select o from Ocorrencia o
            where o.statusValidacao = :status
              and o.latitude between :latMin and :latMax
              and o.longitude between :lonMin and :lonMax
              and o.dataOcorrencia >= :desde
            order by o.dataOcorrencia desc
            """)
    List<Ocorrencia> buscarNaArea(@Param("status") StatusValidacao status,
                                  @Param("latMin") double latMin, @Param("latMax") double latMax,
                                  @Param("lonMin") double lonMin, @Param("lonMax") double lonMax,
                                  @Param("desde") LocalDate desde);

    /** Registros oficiais municipais (sem coordenadas) de um município a partir de uma data. */
    @EntityGraph(attributePaths = {"fonte"})
    List<Ocorrencia> findByCodigoIbgeAndNivelLocalizacaoAndStatusValidacaoAndDataOcorrenciaGreaterThanEqualOrderByDataOcorrenciaDesc(
            String codigoIbge, NivelLocalizacao nivel, StatusValidacao status, LocalDate desde);

    /** Indica se já existem registros oficiais municipais importados para a UF (cobertura da base). */
    boolean existsByEstadoAndOrigemAndNivelLocalizacao(String estado, OrigemOcorrencia origem, NivelLocalizacao nivel);

    boolean existsByFonteAndIdentificadorExterno(FonteDados fonte, String identificadorExterno);

    /** Listagem pública: somente ocorrências confirmadas, filtrando opcionalmente por UF e município. */
    @EntityGraph(attributePaths = {"fonte"})
    @Query("""
            select o from Ocorrencia o
            where o.statusValidacao = com.alertalar.entity.enums.StatusValidacao.CONFIRMADA
              and (:estado is null or o.estado = :estado)
              and (:municipio is null or lower(o.municipio) = :municipio)
              and (:origem is null or o.origem = :origem)
            """)
    Page<Ocorrencia> listarConfirmadas(@Param("estado") String estado,
                                       @Param("municipio") String municipioMinusculo,
                                       @Param("origem") OrigemOcorrencia origem,
                                       Pageable pageable);

    @EntityGraph(attributePaths = {"fonte"})
    Page<Ocorrencia> findByStatusValidacao(StatusValidacao status, Pageable pageable);

    long countByStatusValidacao(StatusValidacao status);
}
