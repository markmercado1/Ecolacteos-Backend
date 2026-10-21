package com.planta.acopio.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.UUID;

// Insumo usado en un lote de produccion (V16__lote_produccion_insumo.sql). Mismo shape que
// InformeProduccionInsumo, pero por lote: trazabilidad de con que se elaboro cada producto terminado.
// UNIQUE(lote_id, insumo_id): un insumo no se repite dentro del mismo lote.
@Entity
@Table(name = "lote_produccion_insumo")
public class LoteProduccionInsumo {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "lote_id")
    private LoteProduccion lote;

    @ManyToOne(optional = false)
    @JoinColumn(name = "insumo_id")
    private Insumo insumo;

    @Column(nullable = false, precision = 9, scale = 2)
    private BigDecimal cantidad;

    public LoteProduccionInsumo() {}

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public LoteProduccion getLote() { return lote; }
    public void setLote(LoteProduccion lote) { this.lote = lote; }
    public Insumo getInsumo() { return insumo; }
    public void setInsumo(Insumo insumo) { this.insumo = insumo; }
    public BigDecimal getCantidad() { return cantidad; }
    public void setCantidad(BigDecimal cantidad) { this.cantidad = cantidad; }
}
