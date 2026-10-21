package com.planta.acopio.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;

// Fase 1 (V21): reparto de un movimiento del libro entre lotes (tabla hija de movimiento_inventario, que NO se
// modifica). cantidad lleva el MISMO signo que el movimiento. Origen: un lote de produccion (loteDetalle), una
// regularizacion (Fase 2, V22) o ninguno de los dos = existencias sin trazabilidad. Nunca ambos. Append-only:
// nunca se edita ni se borra.
@Entity
@Table(name = "movimiento_inventario_lote")
public class AsignacionLote {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "movimiento_id")
    private MovimientoInventario movimiento;

    @ManyToOne
    @JoinColumn(name = "lote_detalle_id")
    private LoteProduccionDetalle loteDetalle;

    @ManyToOne
    @JoinColumn(name = "regularizacion_id")
    private RegularizacionTrazabilidad regularizacion;

    @Column(nullable = false)
    private Integer cantidad;

    @Column(name = "creado_en", nullable = false, updatable = false)
    private LocalDateTime creadoEn = LocalDateTime.now();

    public AsignacionLote() {}

    public AsignacionLote(MovimientoInventario movimiento, LoteProduccionDetalle loteDetalle, Integer cantidad) {
        this.movimiento = movimiento;
        this.loteDetalle = loteDetalle;
        this.cantidad = cantidad;
    }

    public AsignacionLote(MovimientoInventario movimiento, RegularizacionTrazabilidad regularizacion, Integer cantidad) {
        this.movimiento = movimiento;
        this.regularizacion = regularizacion;
        this.cantidad = cantidad;
    }

    /** Reparto contra las existencias sin trazabilidad (ni lote ni regularizacion). */
    public static AsignacionLote sinTrazabilidad(MovimientoInventario movimiento, Integer cantidad) {
        return new AsignacionLote(movimiento, (LoteProduccionDetalle) null, cantidad);
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public MovimientoInventario getMovimiento() { return movimiento; }
    public void setMovimiento(MovimientoInventario movimiento) { this.movimiento = movimiento; }
    public LoteProduccionDetalle getLoteDetalle() { return loteDetalle; }
    public void setLoteDetalle(LoteProduccionDetalle loteDetalle) { this.loteDetalle = loteDetalle; }
    public RegularizacionTrazabilidad getRegularizacion() { return regularizacion; }
    public void setRegularizacion(RegularizacionTrazabilidad regularizacion) { this.regularizacion = regularizacion; }
    public Integer getCantidad() { return cantidad; }
    public void setCantidad(Integer cantidad) { this.cantidad = cantidad; }
    public LocalDateTime getCreadoEn() { return creadoEn; }
    public void setCreadoEn(LocalDateTime creadoEn) { this.creadoEn = creadoEn; }
}
