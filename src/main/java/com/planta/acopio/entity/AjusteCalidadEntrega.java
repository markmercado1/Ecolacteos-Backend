package com.planta.acopio.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

// Fase C: precio por litro de UNA entrega de un pago por razones de calidad. Append-only (sin update ni delete): corregirlo es
// registrar otro ajuste. Siempre lleva el analisis de calidad que lo sustenta (de esa misma entrega) y un motivo.
@Entity
@Table(name = "ajuste_calidad_entrega")
public class AjusteCalidadEntrega {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "pago_linea_id", updatable = false)
    private LineaPago pagoLinea;

    @Column(name = "precio_anterior", nullable = false, updatable = false, precision = 6, scale = 3)
    private BigDecimal precioAnterior;

    @Column(name = "precio_nuevo", nullable = false, updatable = false, precision = 6, scale = 3)
    private BigDecimal precioNuevo;

    @ManyToOne(optional = false)
    @JoinColumn(name = "analisis_calidad_id", updatable = false)
    private AnalisisCalidad analisisCalidad;

    @Column(nullable = false, updatable = false, columnDefinition = "TEXT")
    private String motivo;

    @ManyToOne(optional = false)
    @JoinColumn(name = "usuario_id", updatable = false)
    private Usuario usuario;

    @Column(name = "creado_en", nullable = false, updatable = false)
    private LocalDateTime creadoEn = LocalDateTime.now();

    public AjusteCalidadEntrega() {}

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public LineaPago getPagoLinea() { return pagoLinea; }
    public void setPagoLinea(LineaPago pagoLinea) { this.pagoLinea = pagoLinea; }
    public BigDecimal getPrecioAnterior() { return precioAnterior; }
    public void setPrecioAnterior(BigDecimal precioAnterior) { this.precioAnterior = precioAnterior; }
    public BigDecimal getPrecioNuevo() { return precioNuevo; }
    public void setPrecioNuevo(BigDecimal precioNuevo) { this.precioNuevo = precioNuevo; }
    public AnalisisCalidad getAnalisisCalidad() { return analisisCalidad; }
    public void setAnalisisCalidad(AnalisisCalidad analisisCalidad) { this.analisisCalidad = analisisCalidad; }
    public String getMotivo() { return motivo; }
    public void setMotivo(String motivo) { this.motivo = motivo; }
    public Usuario getUsuario() { return usuario; }
    public void setUsuario(Usuario usuario) { this.usuario = usuario; }
    public LocalDateTime getCreadoEn() { return creadoEn; }
    public void setCreadoEn(LocalDateTime creadoEn) { this.creadoEn = creadoEn; }
}
