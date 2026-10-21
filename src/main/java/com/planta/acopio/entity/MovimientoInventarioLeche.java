package com.planta.acopio.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

// Libro mayor de leche (V8__inventario_leche.sql), append-only: mismo criterio exacto que
// MovimientoInventario (queso, V7) -- la existencia de leche disponible para producir es siempre
// SUM(cantidad) de esta tabla, nunca una columna mutable. No hay movimientos retroactivos para
// recepcion_planta anteriores a esta migracion (mismo alcance explicito que V7 tuvo para lote_produccion).
@Entity
@Table(name = "movimiento_inventario_leche")
public class MovimientoInventarioLeche {

    @Id
    @GeneratedValue
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "tipo_movimiento", nullable = false, length = 25)
    private TipoMovimientoInventarioLeche tipoMovimiento;

    // Positiva en ENTRADA_LECHE_RECEPCION, negativa en SALIDA_LECHE_PRODUCCION (ver checks en la migracion).
    // Litros, no unidades -- BigDecimal, a diferencia de MovimientoInventario.cantidad (Integer, unidades de queso).
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal cantidad;

    @ManyToOne
    @JoinColumn(name = "recepcion_planta_id")
    private RecepcionPlanta recepcionPlanta;

    // Poblado recien desde el refactor que consume este saldo (LoteProduccionService, fase siguiente):
    // ninguna fila lo usa todavia, pero la columna/relacion existe desde ya para no partir esta tabla en dos.
    @ManyToOne
    @JoinColumn(name = "lote_produccion_id")
    private LoteProduccion loteProduccion;

    @Column(name = "creado_en", nullable = false, updatable = false)
    private LocalDateTime creadoEn = LocalDateTime.now();

    public MovimientoInventarioLeche() {}

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public TipoMovimientoInventarioLeche getTipoMovimiento() { return tipoMovimiento; }
    public void setTipoMovimiento(TipoMovimientoInventarioLeche tipoMovimiento) { this.tipoMovimiento = tipoMovimiento; }
    public BigDecimal getCantidad() { return cantidad; }
    public void setCantidad(BigDecimal cantidad) { this.cantidad = cantidad; }
    public RecepcionPlanta getRecepcionPlanta() { return recepcionPlanta; }
    public void setRecepcionPlanta(RecepcionPlanta recepcionPlanta) { this.recepcionPlanta = recepcionPlanta; }
    public LoteProduccion getLoteProduccion() { return loteProduccion; }
    public void setLoteProduccion(LoteProduccion loteProduccion) { this.loteProduccion = loteProduccion; }
    public LocalDateTime getCreadoEn() { return creadoEn; }
    public void setCreadoEn(LocalDateTime creadoEn) { this.creadoEn = creadoEn; }
}
