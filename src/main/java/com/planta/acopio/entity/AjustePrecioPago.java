package com.planta.acopio.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

// Cambio del precio por litro de UN pago (V24__ajuste_precio_pago.sql). Solo CREATE, nunca UPDATE/DELETE: el Pago
// sigue inmutable y el historial completo (quien, cuando, por que, de cuanto a cuanto) queda en esta tabla.
@Entity
@Table(name = "ajuste_precio_pago")
public class AjustePrecioPago {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "pago_id")
    private Pago pago;

    @Column(name = "precio_litro_anterior", nullable = false, precision = 6, scale = 3)
    private BigDecimal precioLitroAnterior;

    @Column(name = "precio_litro_nuevo", nullable = false, precision = 6, scale = 3)
    private BigDecimal precioLitroNuevo;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String motivo;

    @ManyToOne(optional = false)
    @JoinColumn(name = "usuario_id")
    private Usuario usuario;

    @Column(name = "creado_en", nullable = false, updatable = false)
    private LocalDateTime creadoEn = LocalDateTime.now();

    public AjustePrecioPago() {}

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public Pago getPago() { return pago; }
    public void setPago(Pago pago) { this.pago = pago; }
    public BigDecimal getPrecioLitroAnterior() { return precioLitroAnterior; }
    public void setPrecioLitroAnterior(BigDecimal precioLitroAnterior) { this.precioLitroAnterior = precioLitroAnterior; }
    public BigDecimal getPrecioLitroNuevo() { return precioLitroNuevo; }
    public void setPrecioLitroNuevo(BigDecimal precioLitroNuevo) { this.precioLitroNuevo = precioLitroNuevo; }
    public String getMotivo() { return motivo; }
    public void setMotivo(String motivo) { this.motivo = motivo; }
    public Usuario getUsuario() { return usuario; }
    public void setUsuario(Usuario usuario) { this.usuario = usuario; }
    public LocalDateTime getCreadoEn() { return creadoEn; }
    public void setCreadoEn(LocalDateTime creadoEn) { this.creadoEn = creadoEn; }
}
