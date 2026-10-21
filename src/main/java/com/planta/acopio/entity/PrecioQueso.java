package com.planta.acopio.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

// Fase 2 (Precios por presentacion, V11__precio_presentacion.sql): serie historica append-only por
// PRESENTACION + modalidad (reutiliza TipoClienteVenta) -- cada presentacion tiene sus propias tarifas,
// nunca se deduce el precio de una a partir de otra. Sin editar/eliminar (mismo criterio que PrecioLitro).
// Nombre de clase/tabla conservado por decision explicita (reutilizar, no reconstruir) aunque ya no
// referencia Producto directamente -- ver PrecioQuesoService.
@Entity
@Table(name = "precio_presentacion")
public class PrecioQueso {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "presentacion_id")
    private Presentacion presentacion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 15)
    private TipoClienteVenta modalidad;

    @Column(nullable = false, precision = 8, scale = 2)
    private BigDecimal precio;

    @Column(name = "vigente_desde", nullable = false)
    private LocalDate vigenteDesde;

    @Column(name = "creado_en", nullable = false, updatable = false)
    private LocalDateTime creadoEn = LocalDateTime.now();

    public PrecioQueso() {}

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public Presentacion getPresentacion() { return presentacion; }
    public void setPresentacion(Presentacion presentacion) { this.presentacion = presentacion; }
    public TipoClienteVenta getModalidad() { return modalidad; }
    public void setModalidad(TipoClienteVenta modalidad) { this.modalidad = modalidad; }
    public BigDecimal getPrecio() { return precio; }
    public void setPrecio(BigDecimal precio) { this.precio = precio; }
    public LocalDate getVigenteDesde() { return vigenteDesde; }
    public void setVigenteDesde(LocalDate vigenteDesde) { this.vigenteDesde = vigenteDesde; }
    public LocalDateTime getCreadoEn() { return creadoEn; }
    public void setCreadoEn(LocalDateTime creadoEn) { this.creadoEn = creadoEn; }
}
