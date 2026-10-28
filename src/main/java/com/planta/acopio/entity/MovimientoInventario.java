package com.planta.acopio.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;

// Libro mayor de movimientos de inventario (V7__inventario_precio_queso.sql), append-only: nunca se edita
// ni se borra. El stock de una presentacion es siempre SUM(cantidad) agrupado por presentacion_id -- no
// existe una tabla de stock mutable (ver InventarioService).
//
// Fase 3 (Produccion con varias presentaciones de salida, V12__produccion_multi_presentacion.sql): el stock
// pasa de calcularse por PRODUCTO a calcularse por PRESENTACION (una entrada por detalle de produccion, no
// una por lote -- un lote con 3 salidas genera 3 movimientos ENTRADA_PRODUCCION, uno por presentacion). El
// origen de una entrada es su LoteProduccionDetalle (nunca el LoteProduccion directamente, para que el
// indice unico admita varias entradas por lote sin duplicar la misma presentacion dos veces).
@Entity
@Table(name = "movimiento_inventario")
public class MovimientoInventario {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "presentacion_id")
    private Presentacion presentacion;

    @Enumerated(EnumType.STRING)
    @Column(name = "tipo_movimiento", nullable = false, length = 20)
    private TipoMovimientoInventario tipoMovimiento;

    // Positiva en ENTRADA_PRODUCCION, negativa en SALIDA_VENTA (ver checks en la migracion).
    @Column(nullable = false)
    private Integer cantidad;

    @ManyToOne
    @JoinColumn(name = "lote_produccion_detalle_id")
    private LoteProduccionDetalle loteProduccionDetalle;

    // Fase 4 (Venta multi-linea / carrito, V15__venta_multi_linea.sql): el origen de una salida es su
    // VentaDetalle (nunca la Venta directamente), mismo criterio que ENTRADA_PRODUCCION con
    // LoteProduccionDetalle -- una venta con 3 lineas genera 3 movimientos legitimos, cada linea sigue
    // generando como maximo uno.
    @ManyToOne
    @JoinColumn(name = "venta_detalle_id")
    private VentaDetalle ventaDetalle;

    // Inventario: accion "Ajustar stock" (V14__ajuste_inventario.sql) -- correccion manual por conteo
    // fisico, nunca ligada a un lote ni a una venta (ver chk_movimiento_ajuste).
    @ManyToOne
    @JoinColumn(name = "ajuste_inventario_id")
    private AjusteInventario ajusteInventario;

    @Column(name = "creado_en", nullable = false, updatable = false)
    private LocalDateTime creadoEn = LocalDateTime.now();

    @ManyToOne
    @JoinColumn(name = "salida_id")
    private SalidaInventario salida;
    public SalidaInventario getSalida() { return salida; }
    public void setSalida(SalidaInventario salida) { this.salida = salida; }

    public MovimientoInventario() {}

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public Presentacion getPresentacion() { return presentacion; }
    public void setPresentacion(Presentacion presentacion) { this.presentacion = presentacion; }
    public TipoMovimientoInventario getTipoMovimiento() { return tipoMovimiento; }
    public void setTipoMovimiento(TipoMovimientoInventario tipoMovimiento) { this.tipoMovimiento = tipoMovimiento; }
    public Integer getCantidad() { return cantidad; }
    public void setCantidad(Integer cantidad) { this.cantidad = cantidad; }
    public LoteProduccionDetalle getLoteProduccionDetalle() { return loteProduccionDetalle; }
    public void setLoteProduccionDetalle(LoteProduccionDetalle loteProduccionDetalle) { this.loteProduccionDetalle = loteProduccionDetalle; }
    public VentaDetalle getVentaDetalle() { return ventaDetalle; }
    public void setVentaDetalle(VentaDetalle ventaDetalle) { this.ventaDetalle = ventaDetalle; }
    public AjusteInventario getAjusteInventario() { return ajusteInventario; }
    public void setAjusteInventario(AjusteInventario ajusteInventario) { this.ajusteInventario = ajusteInventario; }
    public LocalDateTime getCreadoEn() { return creadoEn; }
    public void setCreadoEn(LocalDateTime creadoEn) { this.creadoEn = creadoEn; }
}
