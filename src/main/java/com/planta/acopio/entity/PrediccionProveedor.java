package com.planta.acopio.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

// Escrita unicamente por AnomaliaService.recalcularPrediccionProveedor
// (job semanal) -- RF-INN-03 (parte proactiva).
@Entity
@Table(name = "prediccion_proveedor")
public class PrediccionProveedor {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "proveedor_id")
    private Proveedor proveedor;

    @Column(name = "fecha_prevista", nullable = false)
    private LocalDate fechaPrevista;

    @Column(name = "litros_estimados_min", nullable = false, precision = 8, scale = 2)
    private BigDecimal litrosEstimadosMin;

    @Column(name = "litros_estimados_max", nullable = false, precision = 8, scale = 2)
    private BigDecimal litrosEstimadosMax;

    @Column(name = "generado_en", nullable = false, updatable = false)
    private LocalDateTime generadoEn = LocalDateTime.now();

    public PrediccionProveedor() {}

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public Proveedor getProveedor() { return proveedor; }
    public void setProveedor(Proveedor proveedor) { this.proveedor = proveedor; }
    public LocalDate getFechaPrevista() { return fechaPrevista; }
    public void setFechaPrevista(LocalDate fechaPrevista) { this.fechaPrevista = fechaPrevista; }
    public BigDecimal getLitrosEstimadosMin() { return litrosEstimadosMin; }
    public void setLitrosEstimadosMin(BigDecimal litrosEstimadosMin) { this.litrosEstimadosMin = litrosEstimadosMin; }
    public BigDecimal getLitrosEstimadosMax() { return litrosEstimadosMax; }
    public void setLitrosEstimadosMax(BigDecimal litrosEstimadosMax) { this.litrosEstimadosMax = litrosEstimadosMax; }
    public LocalDateTime getGeneradoEn() { return generadoEn; }
    public void setGeneradoEn(LocalDateTime generadoEn) { this.generadoEn = generadoEn; }
}
