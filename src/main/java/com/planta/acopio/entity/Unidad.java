package com.planta.acopio.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "unidad")
public class Unidad {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false, unique = true, length = 20)
    private String placa;

    @Column(name = "capacidad_ton", precision = 5, scale = 2)
    private BigDecimal capacidadTon;

    @ManyToOne
    @JoinColumn(name = "zona_id")
    private Zona zona;

    // mejora #2 (CORE_DESIGN.md §6): obligatorio, decisión aprobada.
    @ManyToOne(optional = false)
    @JoinColumn(name = "responsable_id", nullable = false)
    private Usuario responsable;

    public Unidad() {}

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getPlaca() { return placa; }
    public void setPlaca(String placa) { this.placa = placa; }
    public BigDecimal getCapacidadTon() { return capacidadTon; }
    public void setCapacidadTon(BigDecimal capacidadTon) { this.capacidadTon = capacidadTon; }
    public Zona getZona() { return zona; }
    public void setZona(Zona zona) { this.zona = zona; }
    public Usuario getResponsable() { return responsable; }
    public void setResponsable(Usuario responsable) { this.responsable = responsable; }
}
