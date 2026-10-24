package com.planta.acopio.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

// Pagos efectivos: parte de un desembolso aplicada a UNA liquidacion (V30). Inmutable (sin update ni delete): solo cuentan las
// aplicaciones de desembolsos no anulados.
@Entity
@Table(name = "pago_proveedor_aplicacion")
public class PagoProveedorAplicacion {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "pago_proveedor_id", updatable = false)
    private PagoProveedor pagoProveedor;

    @ManyToOne(optional = false)
    @JoinColumn(name = "pago_id", updatable = false)
    private Pago pago;

    @Column(nullable = false, updatable = false, precision = 12, scale = 2)
    private BigDecimal monto;

    @ManyToOne(optional = false)
    @JoinColumn(name = "usuario_id", updatable = false)
    private Usuario usuario;

    @Column(name = "creado_en", nullable = false, updatable = false)
    private LocalDateTime creadoEn = LocalDateTime.now();

    public PagoProveedorAplicacion() {}

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public PagoProveedor getPagoProveedor() { return pagoProveedor; }
    public void setPagoProveedor(PagoProveedor pagoProveedor) { this.pagoProveedor = pagoProveedor; }
    public Pago getPago() { return pago; }
    public void setPago(Pago pago) { this.pago = pago; }
    public BigDecimal getMonto() { return monto; }
    public void setMonto(BigDecimal monto) { this.monto = monto; }
    public Usuario getUsuario() { return usuario; }
    public void setUsuario(Usuario usuario) { this.usuario = usuario; }
    public LocalDateTime getCreadoEn() { return creadoEn; }
    public void setCreadoEn(LocalDateTime creadoEn) { this.creadoEn = creadoEn; }
}
