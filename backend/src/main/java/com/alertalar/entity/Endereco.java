package com.alertalar.entity;

import com.alertalar.entity.enums.PrecisaoGeocodificacao;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;

/** Endereço geocodificado. Também serve de cache para não repetir chamadas ao Nominatim. */
@Entity
@Table(name = "endereco")
public class Endereco {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 9)
    private String cep;

    @Column(length = 200)
    private String rua;

    @Column(length = 20)
    private String numero;

    @Column(length = 120)
    private String bairro;

    @Column(nullable = false, length = 120)
    private String cidade;

    @Column(nullable = false, length = 2)
    private String estado;

    @Column(name = "codigo_ibge", length = 7)
    private String codigoIbge;

    @Column(nullable = false)
    private Double latitude;

    @Column(nullable = false)
    private Double longitude;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PrecisaoGeocodificacao precisao;

    @Column(name = "fonte_geocodificacao", length = 60)
    private String fonteGeocodificacao;

    @Column(name = "chave_normalizada", length = 500)
    private String chaveNormalizada;

    @Column(name = "data_geocodificacao", nullable = false)
    private Instant dataGeocodificacao;

    public Endereco() {
    }

    @PrePersist
    void aoCriar() {
        if (dataGeocodificacao == null) {
            dataGeocodificacao = Instant.now();
        }
    }

    /** Texto amigável do endereço, ex.: "Rua X, 100 - Centro, Canoas/RS". */
    public String descricaoCompleta() {
        StringBuilder sb = new StringBuilder();
        if (rua != null && !rua.isBlank()) {
            sb.append(rua);
            if (numero != null && !numero.isBlank()) {
                sb.append(", ").append(numero);
            }
        }
        if (bairro != null && !bairro.isBlank()) {
            if (!sb.isEmpty()) {
                sb.append(" - ");
            }
            sb.append(bairro);
        }
        if (!sb.isEmpty()) {
            sb.append(", ");
        }
        sb.append(cidade).append('/').append(estado);
        if (cep != null && !cep.isBlank()) {
            sb.append(" - CEP ").append(cep);
        }
        return sb.toString();
    }

    public Long getId() { return id; }
    public String getCep() { return cep; }
    public void setCep(String cep) { this.cep = cep; }
    public String getRua() { return rua; }
    public void setRua(String rua) { this.rua = rua; }
    public String getNumero() { return numero; }
    public void setNumero(String numero) { this.numero = numero; }
    public String getBairro() { return bairro; }
    public void setBairro(String bairro) { this.bairro = bairro; }
    public String getCidade() { return cidade; }
    public void setCidade(String cidade) { this.cidade = cidade; }
    public String getEstado() { return estado; }
    public void setEstado(String estado) { this.estado = estado; }
    public String getCodigoIbge() { return codigoIbge; }
    public void setCodigoIbge(String codigoIbge) { this.codigoIbge = codigoIbge; }
    public Double getLatitude() { return latitude; }
    public void setLatitude(Double latitude) { this.latitude = latitude; }
    public Double getLongitude() { return longitude; }
    public void setLongitude(Double longitude) { this.longitude = longitude; }
    public PrecisaoGeocodificacao getPrecisao() { return precisao; }
    public void setPrecisao(PrecisaoGeocodificacao precisao) { this.precisao = precisao; }
    public String getFonteGeocodificacao() { return fonteGeocodificacao; }
    public void setFonteGeocodificacao(String fonteGeocodificacao) { this.fonteGeocodificacao = fonteGeocodificacao; }
    public String getChaveNormalizada() { return chaveNormalizada; }
    public void setChaveNormalizada(String chaveNormalizada) { this.chaveNormalizada = chaveNormalizada; }
    public Instant getDataGeocodificacao() { return dataGeocodificacao; }
    public void setDataGeocodificacao(Instant dataGeocodificacao) { this.dataGeocodificacao = dataGeocodificacao; }
}
