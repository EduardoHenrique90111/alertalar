package com.alertalar.entity;

import com.alertalar.entity.enums.CategoriaFator;
import com.alertalar.entity.enums.SituacaoFator;
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
import jakarta.persistence.Table;

import java.time.Instant;

/** Resultado de um fator individual dentro de uma consulta (base da justificativa). */
@Entity
@Table(name = "consulta_fator")
public class FatorConsulta {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "consulta_id", nullable = false)
    private Consulta consulta;

    @Column(nullable = false)
    private Integer ordem;

    @Column(nullable = false, length = 40)
    private String codigo;

    @Column(nullable = false, length = 120)
    private String nome;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private CategoriaFator categoria;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SituacaoFator situacao;

    /** 0 (sem indício) a 3 (indício forte). Nulo quando não avaliado. */
    @Column
    private Integer pontuacao;

    @Column(nullable = false)
    private Double peso;

    @Column(name = "valor_observado", length = 500)
    private String valorObservado;

    @Column(nullable = false, length = 2000)
    private String explicacao;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "fonte_id")
    private FonteDados fonte;

    @Column(name = "data_referencia")
    private Instant dataReferencia;

    public FatorConsulta() {
    }

    public Long getId() { return id; }
    public Consulta getConsulta() { return consulta; }
    void setConsulta(Consulta consulta) { this.consulta = consulta; }
    public Integer getOrdem() { return ordem; }
    public void setOrdem(Integer ordem) { this.ordem = ordem; }
    public String getCodigo() { return codigo; }
    public void setCodigo(String codigo) { this.codigo = codigo; }
    public String getNome() { return nome; }
    public void setNome(String nome) { this.nome = nome; }
    public CategoriaFator getCategoria() { return categoria; }
    public void setCategoria(CategoriaFator categoria) { this.categoria = categoria; }
    public SituacaoFator getSituacao() { return situacao; }
    public void setSituacao(SituacaoFator situacao) { this.situacao = situacao; }
    public Integer getPontuacao() { return pontuacao; }
    public void setPontuacao(Integer pontuacao) { this.pontuacao = pontuacao; }
    public Double getPeso() { return peso; }
    public void setPeso(Double peso) { this.peso = peso; }
    public String getValorObservado() { return valorObservado; }
    public void setValorObservado(String valorObservado) { this.valorObservado = valorObservado; }
    public String getExplicacao() { return explicacao; }
    public void setExplicacao(String explicacao) { this.explicacao = explicacao; }
    public FonteDados getFonte() { return fonte; }
    public void setFonte(FonteDados fonte) { this.fonte = fonte; }
    public Instant getDataReferencia() { return dataReferencia; }
    public void setDataReferencia(Instant dataReferencia) { this.dataReferencia = dataReferencia; }
}
