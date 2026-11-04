package com.alertalar.entity;

import com.alertalar.entity.enums.NivelLocalizacao;
import com.alertalar.entity.enums.OrigemOcorrencia;
import com.alertalar.entity.enums.StatusValidacao;
import com.alertalar.entity.enums.TipoEvento;
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
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Ocorrência de enchente/alagamento. Pode ser um registro oficial (importado de fonte
 * governamental) ou um relato de usuário. Relatos começam como PENDENTE e só entram
 * na análise de risco depois de CONFIRMADOS por um administrador.
 */
@Entity
@Table(name = "ocorrencia")
public class Ocorrencia {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "endereco_id")
    private Endereco endereco;

    /** Descrição textual do local (endereço ou referência aproximada). */
    @Column(nullable = false, length = 300)
    private String localizacao;

    @Column(length = 120)
    private String municipio;

    @Column(length = 2)
    private String estado;

    @Column(name = "codigo_ibge", length = 7)
    private String codigoIbge;

    @Column(name = "data_ocorrencia", nullable = false)
    private LocalDate dataOcorrencia;

    @Column(nullable = false, length = 2000)
    private String descricao;

    @Enumerated(EnumType.STRING)
    @Column(name = "tipo_evento", nullable = false, length = 20)
    private TipoEvento tipoEvento;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrigemOcorrencia origem;

    @Enumerated(EnumType.STRING)
    @Column(name = "status_validacao", nullable = false, length = 20)
    private StatusValidacao statusValidacao;

    @Enumerated(EnumType.STRING)
    @Column(name = "nivel_localizacao", nullable = false, length = 20)
    private NivelLocalizacao nivelLocalizacao;

    @Column
    private Double latitude;

    @Column
    private Double longitude;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "fonte_id", nullable = false)
    private FonteDados fonte;

    /** Identificador do registro na fonte de origem (ex.: protocolo do S2iD), usado para evitar duplicidade. */
    @Column(name = "identificador_externo", length = 120)
    private String identificadorExterno;

    /** Identificação opcional de quem relatou. Nunca é exibida publicamente. */
    @Column(name = "nome_relator", length = 120)
    private String nomeRelator;

    @Column(name = "contato_relator", length = 254)
    private String contatoRelator;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "usuario_id")
    private Usuario usuario;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "validado_por_id")
    private Usuario validadoPor;

    @Column(name = "data_validacao")
    private Instant dataValidacao;

    @Column(name = "observacao_validacao", length = 500)
    private String observacaoValidacao;

    @Column(name = "data_registro", nullable = false)
    private Instant dataRegistro;

    public Ocorrencia() {
    }

    @PrePersist
    void aoCriar() {
        if (dataRegistro == null) {
            dataRegistro = Instant.now();
        }
    }

    public boolean possuiCoordenadas() {
        return latitude != null && longitude != null;
    }

    public Long getId() { return id; }
    public Endereco getEndereco() { return endereco; }
    public void setEndereco(Endereco endereco) { this.endereco = endereco; }
    public String getLocalizacao() { return localizacao; }
    public void setLocalizacao(String localizacao) { this.localizacao = localizacao; }
    public String getMunicipio() { return municipio; }
    public void setMunicipio(String municipio) { this.municipio = municipio; }
    public String getEstado() { return estado; }
    public void setEstado(String estado) { this.estado = estado; }
    public String getCodigoIbge() { return codigoIbge; }
    public void setCodigoIbge(String codigoIbge) { this.codigoIbge = codigoIbge; }
    public LocalDate getDataOcorrencia() { return dataOcorrencia; }
    public void setDataOcorrencia(LocalDate dataOcorrencia) { this.dataOcorrencia = dataOcorrencia; }
    public String getDescricao() { return descricao; }
    public void setDescricao(String descricao) { this.descricao = descricao; }
    public TipoEvento getTipoEvento() { return tipoEvento; }
    public void setTipoEvento(TipoEvento tipoEvento) { this.tipoEvento = tipoEvento; }
    public OrigemOcorrencia getOrigem() { return origem; }
    public void setOrigem(OrigemOcorrencia origem) { this.origem = origem; }
    public StatusValidacao getStatusValidacao() { return statusValidacao; }
    public void setStatusValidacao(StatusValidacao statusValidacao) { this.statusValidacao = statusValidacao; }
    public NivelLocalizacao getNivelLocalizacao() { return nivelLocalizacao; }
    public void setNivelLocalizacao(NivelLocalizacao nivelLocalizacao) { this.nivelLocalizacao = nivelLocalizacao; }
    public Double getLatitude() { return latitude; }
    public void setLatitude(Double latitude) { this.latitude = latitude; }
    public Double getLongitude() { return longitude; }
    public void setLongitude(Double longitude) { this.longitude = longitude; }
    public FonteDados getFonte() { return fonte; }
    public void setFonte(FonteDados fonte) { this.fonte = fonte; }
    public String getIdentificadorExterno() { return identificadorExterno; }
    public void setIdentificadorExterno(String identificadorExterno) { this.identificadorExterno = identificadorExterno; }
    public String getNomeRelator() { return nomeRelator; }
    public void setNomeRelator(String nomeRelator) { this.nomeRelator = nomeRelator; }
    public String getContatoRelator() { return contatoRelator; }
    public void setContatoRelator(String contatoRelator) { this.contatoRelator = contatoRelator; }
    public Usuario getUsuario() { return usuario; }
    public void setUsuario(Usuario usuario) { this.usuario = usuario; }
    public Usuario getValidadoPor() { return validadoPor; }
    public void setValidadoPor(Usuario validadoPor) { this.validadoPor = validadoPor; }
    public Instant getDataValidacao() { return dataValidacao; }
    public void setDataValidacao(Instant dataValidacao) { this.dataValidacao = dataValidacao; }
    public String getObservacaoValidacao() { return observacaoValidacao; }
    public void setObservacaoValidacao(String observacaoValidacao) { this.observacaoValidacao = observacaoValidacao; }
    public Instant getDataRegistro() { return dataRegistro; }
    public void setDataRegistro(Instant dataRegistro) { this.dataRegistro = dataRegistro; }
}
