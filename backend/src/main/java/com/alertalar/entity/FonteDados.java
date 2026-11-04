package com.alertalar.entity;

import com.alertalar.entity.enums.StatusIntegracao;
import com.alertalar.entity.enums.TipoFonte;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** Fonte de dados utilizada pelo sistema (API, base geográfica, importação oficial ou relatos). */
@Entity
@Table(name = "fonte_dados")
public class FonteDados {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 60)
    private String codigo;

    @Column(nullable = false, length = 200)
    private String nome;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private TipoFonte tipo;

    @Column(length = 500)
    private String url;

    @Enumerated(EnumType.STRING)
    @Column(name = "status_integracao", nullable = false, length = 30)
    private StatusIntegracao statusIntegracao;

    @Column(nullable = false)
    private boolean oficial;

    /** Último acesso bem-sucedido (APIs) ou última importação (bases oficiais). */
    @Column(name = "data_atualizacao")
    private Instant dataAtualizacao;

    @Column(length = 1000)
    private String descricao;

    @Column(length = 500)
    private String atribuicao;

    protected FonteDados() {
    }

    public FonteDados(String codigo, String nome, TipoFonte tipo, StatusIntegracao statusIntegracao) {
        this.codigo = codigo;
        this.nome = nome;
        this.tipo = tipo;
        this.statusIntegracao = statusIntegracao;
    }

    public Long getId() { return id; }
    public String getCodigo() { return codigo; }
    public String getNome() { return nome; }
    public TipoFonte getTipo() { return tipo; }
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public StatusIntegracao getStatusIntegracao() { return statusIntegracao; }
    public boolean isOficial() { return oficial; }
    public void setOficial(boolean oficial) { this.oficial = oficial; }
    public Instant getDataAtualizacao() { return dataAtualizacao; }
    public void setDataAtualizacao(Instant dataAtualizacao) { this.dataAtualizacao = dataAtualizacao; }
    public String getDescricao() { return descricao; }
    public void setDescricao(String descricao) { this.descricao = descricao; }
    public String getAtribuicao() { return atribuicao; }
    public void setAtribuicao(String atribuicao) { this.atribuicao = atribuicao; }
}
