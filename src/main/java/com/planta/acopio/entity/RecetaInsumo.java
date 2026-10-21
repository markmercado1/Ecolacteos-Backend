package com.planta.acopio.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.UUID;

// Cantidad de un insumo del catalogo existente POR litrosBase de la receta, en la unidadMedida del propio
// insumo (no se convierten unidades). UNIQUE(receta_id, insumo_id).
@Entity
@Table(name = "receta_insumo")
public class RecetaInsumo {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "receta_id")
    private Receta receta;

    @ManyToOne(optional = false)
    @JoinColumn(name = "insumo_id")
    private Insumo insumo;

    @Column(nullable = false, precision = 12, scale = 3)
    private BigDecimal cantidad;

    public RecetaInsumo() {}

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public Receta getReceta() { return receta; }
    public void setReceta(Receta receta) { this.receta = receta; }
    public Insumo getInsumo() { return insumo; }
    public void setInsumo(Insumo insumo) { this.insumo = insumo; }
    public BigDecimal getCantidad() { return cantidad; }
    public void setCantidad(BigDecimal cantidad) { this.cantidad = cantidad; }
}
