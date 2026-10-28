package com.planta.acopio.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/** Documento de retiro, no un segundo libro: el saldo solo procede del movimiento asociado. */
@Entity
@Table(name = "salida_inventario")
public class SalidaInventario {
    @Id
    @GeneratedValue
    private UUID id;
    @Column(name = "uuid_cliente", nullable = false, unique = true)
    private UUID uuidCliente;
    @ManyToOne
    @JoinColumn(name = "presentacion_id")
    private Presentacion presentacion;
    @ManyToOne
    @JoinColumn(name = "insumo_id")
    private Insumo insumo;
    @Column(name = "cantidad", nullable = false, precision = 12, scale = 2)
    private BigDecimal cantidad;
    @Enumerated(EnumType.STRING)
    @Column(name = "tipo", nullable = false, length = 20)
    private MotivoSalida tipo;
    @Column(name = "motivo", nullable = false, length = 500)
    private String motivo;
    @Column(name = "origen_lote", length = 20)
    private String origenLote;
    @Column(name = "lote_id")
    private UUID loteId;
    @Column(name = "fecha_vencimiento")
    private LocalDate fechaVencimiento;
    @ManyToOne
    @JoinColumn(name = "usuario_id")
    private Usuario usuario;
    @Column(name = "creado_en", nullable = false, updatable = false)
    private LocalDateTime creadoEn;
    public UUID getId() { return id; }
    public UUID getUuidCliente() { return uuidCliente; }
    public void setUuidCliente(UUID value) { uuidCliente = value; }
    public Presentacion getPresentacion() { return presentacion; }
    public void setPresentacion(Presentacion value) { presentacion = value; }
    public Insumo getInsumo() { return insumo; }
    public void setInsumo(Insumo value) { insumo = value; }
    public BigDecimal getCantidad() { return cantidad; }
    public void setCantidad(BigDecimal value) { cantidad = value; }
    public MotivoSalida getTipo() { return tipo; }
    public void setTipo(MotivoSalida value) { tipo = value; }
    public String getMotivo() { return motivo; }
    public void setMotivo(String value) { motivo = value; }
    public String getOrigenLote() { return origenLote; }
    public void setOrigenLote(String value) { origenLote = value; }
    public UUID getLoteId() { return loteId; }
    public void setLoteId(UUID value) { loteId = value; }
    public LocalDate getFechaVencimiento() { return fechaVencimiento; }
    public void setFechaVencimiento(LocalDate value) { fechaVencimiento = value; }
    public Usuario getUsuario() { return usuario; }
    public void setUsuario(Usuario value) { usuario = value; }
    public LocalDateTime getCreadoEn() { return creadoEn; }
    public void setCreadoEn(LocalDateTime value) { creadoEn = value; }
}
