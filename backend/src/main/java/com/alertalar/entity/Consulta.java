package com.alertalar.entity;

import com.alertalar.entity.enums.ClassificacaoRisco;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Consulta de risco realizada para um endereço. O identificador é um UUID para que
 * o link do resultado não possa ser adivinhado a partir de números sequenciais.
 */
@Entity
@Table(name = "consulta")
public class Consulta {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "endereco_id", nullable = false)
    private Endereco endereco;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "usuario_id")
    private Usuario usuario;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ClassificacaoRisco classificacao;

    /** Índice de 0 a 100 derivado da média ponderada dos fatores (nulo quando dados insuficientes). */
    @Column
    private Double indice;

    @Column(nullable = false, columnDefinition = "text")
    private String justificativa;

    @Column(name = "versao_metodologia", nullable = false, length = 10)
    private String versaoMetodologia;

    /** Instantâneo (JSON) das geometrias reais exibidas no mapa no momento da consulta. */
    @Column(name = "dados_mapa", columnDefinition = "text")
    private String dadosMapa;

    @Column(name = "data_consulta", nullable = false)
    private Instant dataConsulta;

    @OneToMany(mappedBy = "consulta", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("ordem ASC")
    private List<FatorConsulta> fatores = new ArrayList<>();

    public Consulta() {
    }

    @PrePersist
    void aoCriar() {
        if (dataConsulta == null) {
            dataConsulta = Instant.now();
        }
    }

    public void adicionarFator(FatorConsulta fator) {
        fator.setConsulta(this);
        fatores.add(fator);
    }

    public UUID getId() { return id; }
    public Endereco getEndereco() { return endereco; }
    public void setEndereco(Endereco endereco) { this.endereco = endereco; }
    public Usuario getUsuario() { return usuario; }
    public void setUsuario(Usuario usuario) { this.usuario = usuario; }
    public ClassificacaoRisco getClassificacao() { return classificacao; }
    public void setClassificacao(ClassificacaoRisco classificacao) { this.classificacao = classificacao; }
    public Double getIndice() { return indice; }
    public void setIndice(Double indice) { this.indice = indice; }
    public String getJustificativa() { return justificativa; }
    public void setJustificativa(String justificativa) { this.justificativa = justificativa; }
    public String getVersaoMetodologia() { return versaoMetodologia; }
    public void setVersaoMetodologia(String versaoMetodologia) { this.versaoMetodologia = versaoMetodologia; }
    public String getDadosMapa() { return dadosMapa; }
    public void setDadosMapa(String dadosMapa) { this.dadosMapa = dadosMapa; }
    public Instant getDataConsulta() { return dataConsulta; }
    public void setDataConsulta(Instant dataConsulta) { this.dataConsulta = dataConsulta; }
    public List<FatorConsulta> getFatores() { return fatores; }
}
