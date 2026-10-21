package com.planta.acopio.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

// Escrita unicamente por ForecastingService (job semanal) -- RF-INN-04.
@Entity
@Table(name = "prediccion_zona", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"zona_id", "semana_inicio"})
})
public class PrediccionZona {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "zona_id")
    private Zona zona;

    @Column(name = "semana_inicio", nullable = false)
    private LocalDate semanaInicio;

    @Column(name = "litros_estimados", nullable = false, precision = 10, scale = 2)
    private BigDecimal litrosEstimados;

    @Column(name = "litros_reales", precision = 10, scale = 2)
    private BigDecimal litrosReales;

    @Column(name = "generado_en", nullable = false, updatable = false)
    private LocalDateTime generadoEn = LocalDateTime.now();

    public PrediccionZona() {}

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public Zona getZona() { return zona; }
    public void setZona(Zona zona) { this.zona = zona; }
    public LocalDate getSemanaInicio() { return semanaInicio; }
    public void setSemanaInicio(LocalDate semanaInicio) { this.semanaInicio = semanaInicio; }
    public BigDecimal getLitrosEstimados() { return litrosEstimados; }
    public void setLitrosEstimados(BigDecimal litrosEstimados) { this.litrosEstimados = litrosEstimados; }
    public BigDecimal getLitrosReales() { return litrosReales; }
    public void setLitrosReales(BigDecimal litrosReales) { this.litrosReales = litrosReales; }
    public LocalDateTime getGeneradoEn() { return generadoEn; }
    public void setGeneradoEn(LocalDateTime generadoEn) { this.generadoEn = generadoEn; }
}
