package com.planta.acopio.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

// Libro mayor de insumos (V19__kardex_insumo.sql), append-only: mismo criterio que MovimientoInventarioLeche y
// MovimientoInventario. Solo se escribe desde InventarioInsumoService, en la misma transaccion que actualiza
// insumo.stock (invariante: stock = SUM(cantidad)). Sin setters de negocio para editar: nunca UPDATE/DELETE.
@Entity
@Table(name = "movimiento_insumo")
public class MovimientoInsumo {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "insumo_id")
    private Insumo insumo;

    @Enumerated(EnumType.STRING)
    @Column(name = "tipo_movimiento", nullable = false, length = 20)
    private TipoMovimientoInsumo tipoMovimiento;

    // Positiva entra (saldo inicial, ingreso, ajuste a favor), negativa sale (produccion, ajuste en contra).
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal cantidad;

    @ManyToOne
    @JoinColumn(name = "lote_produccion_id")
    private LoteProduccion loteProduccion;

    @ManyToOne
    @JoinColumn(name = "usuario_id")
    private Usuario usuario;

    @Column(length = 100)
    private String referencia;

    @Column(length = 500)
    private String motivo;

    @Column(name = "creado_en", nullable = false, updatable = false)
    private LocalDateTime creadoEn = LocalDateTime.now();

    @ManyToOne
    @JoinColumn(name = "salida_id")
    private SalidaInventario salida;
    public SalidaInventario getSalida() { return salida; }
    public void setSalida(SalidaInventario salida) { this.salida = salida; }

    public MovimientoInsumo() {}

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public Insumo getInsumo() { return insumo; }
    public void setInsumo(Insumo insumo) { this.insumo = insumo; }
    public TipoMovimientoInsumo getTipoMovimiento() { return tipoMovimiento; }
    public void setTipoMovimiento(TipoMovimientoInsumo tipoMovimiento) { this.tipoMovimiento = tipoMovimiento; }
    public BigDecimal getCantidad() { return cantidad; }
    public void setCantidad(BigDecimal cantidad) { this.cantidad = cantidad; }
    public LoteProduccion getLoteProduccion() { return loteProduccion; }
    public void setLoteProduccion(LoteProduccion loteProduccion) { this.loteProduccion = loteProduccion; }
    public Usuario getUsuario() { return usuario; }
    public void setUsuario(Usuario usuario) { this.usuario = usuario; }
    public String getReferencia() { return referencia; }
    public void setReferencia(String referencia) { this.referencia = referencia; }
    public String getMotivo() { return motivo; }
    public void setMotivo(String motivo) { this.motivo = motivo; }
    public LocalDateTime getCreadoEn() { return creadoEn; }
    public void setCreadoEn(LocalDateTime creadoEn) { this.creadoEn = creadoEn; }
}
