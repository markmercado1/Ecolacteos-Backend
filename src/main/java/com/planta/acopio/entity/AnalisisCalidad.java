package com.planta.acopio.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "analisis_calidad")
public class AnalisisCalidad {

    @Id
    @GeneratedValue
    private UUID id;

    // mejora #1 (CORE_DESIGN.md §6): decision aprobada -- se captura offline igual
    // que RegistroAcopio, por lo tanto necesita la misma clave de idempotencia.
    @Column(name = "uuid_cliente", nullable = false, unique = true, length = 36)
    private String uuidCliente;

    @ManyToOne(optional = false)
    @JoinColumn(name = "registro_acopio_id")
    private RegistroAcopio registroAcopio;

    @Column(name = "folio_muestra", nullable = false, length = 40)
    private String folioMuestra;

    @Column(precision = 5, scale = 2)
    private BigDecimal agua;

    @Column(precision = 5, scale = 2)
    private BigDecimal proteina;

    @Column(precision = 5, scale = 2)
    private BigDecimal lactosa;

    @Column(precision = 6, scale = 2)
    private BigDecimal densidad;

    @Column(precision = 5, scale = 2)
    private BigDecimal temperatura;

    @Column(precision = 4, scale = 2)
    private BigDecimal ph;

    @Column(name = "agua_anadida", nullable = false)
    private Boolean aguaAnadida = false;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 15)
    private ResultadoCalidad resultado = ResultadoCalidad.APROBADO;

    @Column(name = "creado_en", nullable = false, updatable = false)
    private LocalDateTime creadoEn = LocalDateTime.now();

    public AnalisisCalidad() {}

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getUuidCliente() { return uuidCliente; }
    public void setUuidCliente(String uuidCliente) { this.uuidCliente = uuidCliente; }
    public RegistroAcopio getRegistroAcopio() { return registroAcopio; }
    public void setRegistroAcopio(RegistroAcopio registroAcopio) { this.registroAcopio = registroAcopio; }
    public String getFolioMuestra() { return folioMuestra; }
    public void setFolioMuestra(String folioMuestra) { this.folioMuestra = folioMuestra; }
    public BigDecimal getAgua() { return agua; }
    public void setAgua(BigDecimal agua) { this.agua = agua; }
    public BigDecimal getProteina() { return proteina; }
    public void setProteina(BigDecimal proteina) { this.proteina = proteina; }
    public BigDecimal getLactosa() { return lactosa; }
    public void setLactosa(BigDecimal lactosa) { this.lactosa = lactosa; }
    public BigDecimal getDensidad() { return densidad; }
    public void setDensidad(BigDecimal densidad) { this.densidad = densidad; }
    public BigDecimal getTemperatura() { return temperatura; }
    public void setTemperatura(BigDecimal temperatura) { this.temperatura = temperatura; }
    public BigDecimal getPh() { return ph; }
    public void setPh(BigDecimal ph) { this.ph = ph; }
    public Boolean getAguaAnadida() { return aguaAnadida; }
    public void setAguaAnadida(Boolean aguaAnadida) { this.aguaAnadida = aguaAnadida; }
    public ResultadoCalidad getResultado() { return resultado; }
    public void setResultado(ResultadoCalidad resultado) { this.resultado = resultado; }
    public LocalDateTime getCreadoEn() { return creadoEn; }
    public void setCreadoEn(LocalDateTime creadoEn) { this.creadoEn = creadoEn; }
}
