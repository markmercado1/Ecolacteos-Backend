package com.planta.acopio.entity;

import jakarta.persistence.*;
import java.time.LocalTime;
import java.util.UUID;

// Sin endpoint REST propio: se administra desde "definir ruta de zona" (ZonaService).
@Entity
@Table(name = "ruta_proveedor_orden",
       uniqueConstraints = {
           @UniqueConstraint(columnNames = {"zona_id", "proveedor_id"}),
           @UniqueConstraint(columnNames = {"zona_id", "orden"}) // mejora #5
       })
public class RutaProveedorOrden {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "zona_id")
    private Zona zona;

    @ManyToOne(optional = false)
    @JoinColumn(name = "proveedor_id")
    private Proveedor proveedor;

    @Column(nullable = false)
    private Integer orden;

    @Column(name = "hora_estimada")
    private LocalTime horaEstimada;

    public RutaProveedorOrden() {}

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public Zona getZona() { return zona; }
    public void setZona(Zona zona) { this.zona = zona; }
    public Proveedor getProveedor() { return proveedor; }
    public void setProveedor(Proveedor proveedor) { this.proveedor = proveedor; }
    public Integer getOrden() { return orden; }
    public void setOrden(Integer orden) { this.orden = orden; }
    public LocalTime getHoraEstimada() { return horaEstimada; }
    public void setHoraEstimada(LocalTime horaEstimada) { this.horaEstimada = horaEstimada; }
}
