package com.planta.acopio.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.UUID;

// Fase 4 (Venta multi-linea / carrito, V15__venta_multi_linea.sql): una venta corresponde a UNA operacion
// pero puede tener VARIAS lineas (ej. 3 yogures + 2 quesos en una sola venta al mismo cliente). Cada fila es
// una linea real y positiva. UNIQUE(venta, presentacion): una presentacion no puede repetirse en la misma
// venta (evita lineas duplicadas, se valida tambien en VentaService antes de llegar aqui). precioUnitario es
// el precio vigente resuelto por el servidor para ESTA presentacion al momento de la venta (nunca el que
// envia el cliente); subtotal es una columna GENERADA por Postgres (cantidad * precio_unitario), igual
// criterio que el antiguo venta.total de una sola linea.
@Entity
@Table(name = "venta_detalle")
public class VentaDetalle {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "venta_id")
    private Venta venta;

    @ManyToOne(optional = false)
    @JoinColumn(name = "presentacion_id")
    private Presentacion presentacion;

    @Column(nullable = false)
    private Integer cantidad;

    @Column(name = "precio_unitario", nullable = false, precision = 8, scale = 2)
    private BigDecimal precioUnitario;

    // Columna generada por PostgreSQL (STORED) -- solo lectura desde JPA.
    @Column(insertable = false, updatable = false, precision = 10, scale = 2)
    private BigDecimal subtotal;

    public VentaDetalle() {}

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public Venta getVenta() { return venta; }
    public void setVenta(Venta venta) { this.venta = venta; }
    public Presentacion getPresentacion() { return presentacion; }
    public void setPresentacion(Presentacion presentacion) { this.presentacion = presentacion; }
    public Integer getCantidad() { return cantidad; }
    public void setCantidad(Integer cantidad) { this.cantidad = cantidad; }
    public BigDecimal getPrecioUnitario() { return precioUnitario; }
    public void setPrecioUnitario(BigDecimal precioUnitario) { this.precioUnitario = precioUnitario; }
    public BigDecimal getSubtotal() { return subtotal; }
}
