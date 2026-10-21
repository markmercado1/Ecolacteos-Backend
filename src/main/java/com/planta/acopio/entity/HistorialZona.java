package com.planta.acopio.entity;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.util.UUID;

// Sin endpoint REST propio (CORE_DESIGN.md §7): se crea/cierra desde
// ProveedorService al reasignar la zona de un proveedor.
@Entity
@Table(name = "historial_zona")
public class HistorialZona {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "proveedor_id")
    private Proveedor proveedor;

    @ManyToOne(optional = false)
    @JoinColumn(name = "zona_id")
    private Zona zona;

    @Column(nullable = false)
    private LocalDate desde;

    private LocalDate hasta;

    public HistorialZona() {}

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public Proveedor getProveedor() { return proveedor; }
    public void setProveedor(Proveedor proveedor) { this.proveedor = proveedor; }
    public Zona getZona() { return zona; }
    public void setZona(Zona zona) { this.zona = zona; }
    public LocalDate getDesde() { return desde; }
    public void setDesde(LocalDate desde) { this.desde = desde; }
    public LocalDate getHasta() { return hasta; }
    public void setHasta(LocalDate hasta) { this.hasta = hasta; }
}
