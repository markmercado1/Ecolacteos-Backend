package com.planta.acopio.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

// Escrita unicamente por ScoreConfianzaService (job semanal) -- RF-INN-01.
@Entity
@Table(name = "score_confianza_proveedor", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"proveedor_id", "periodo"})
})
public class ScoreConfianzaProveedor {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "proveedor_id")
    private Proveedor proveedor;

    @Column(nullable = false)
    private LocalDate periodo;

    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal score;

    @Column(name = "componente_calidad", nullable = false, precision = 5, scale = 2)
    private BigDecimal componenteCalidad;

    @Column(name = "componente_regularidad", nullable = false, precision = 5, scale = 2)
    private BigDecimal componenteRegularidad;

    @Column(name = "componente_anomalias", nullable = false, precision = 5, scale = 2)
    private BigDecimal componenteAnomalias;

    @Column(name = "calculado_en", nullable = false, updatable = false)
    private LocalDateTime calculadoEn = LocalDateTime.now();

    public ScoreConfianzaProveedor() {}

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public Proveedor getProveedor() { return proveedor; }
    public void setProveedor(Proveedor proveedor) { this.proveedor = proveedor; }
    public LocalDate getPeriodo() { return periodo; }
    public void setPeriodo(LocalDate periodo) { this.periodo = periodo; }
    public BigDecimal getScore() { return score; }
    public void setScore(BigDecimal score) { this.score = score; }
    public BigDecimal getComponenteCalidad() { return componenteCalidad; }
    public void setComponenteCalidad(BigDecimal componenteCalidad) { this.componenteCalidad = componenteCalidad; }
    public BigDecimal getComponenteRegularidad() { return componenteRegularidad; }
    public void setComponenteRegularidad(BigDecimal componenteRegularidad) { this.componenteRegularidad = componenteRegularidad; }
    public BigDecimal getComponenteAnomalias() { return componenteAnomalias; }
    public void setComponenteAnomalias(BigDecimal componenteAnomalias) { this.componenteAnomalias = componenteAnomalias; }
    public LocalDateTime getCalculadoEn() { return calculadoEn; }
    public void setCalculadoEn(LocalDateTime calculadoEn) { this.calculadoEn = calculadoEn; }
}
