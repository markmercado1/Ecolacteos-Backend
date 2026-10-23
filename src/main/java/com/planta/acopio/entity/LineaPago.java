package com.planta.acopio.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

// Fase B: una entrega liquidada en un pago, con los litros EFECTIVAMENTE liquidados congelados al generar el pago.
// Es append-only (no hay update ni delete): una correccion posterior no reescribe la linea, genera un AjustePago.
// Los litros reales recibidos siguen en RegistroAcopio.litros (inmutable); litrosOriginales es solo una copia de auditoria.
@Entity
@Table(name = "pago_linea")
public class LineaPago {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "pago_id", updatable = false)
    private Pago pago;

    @ManyToOne(optional = false)
    @JoinColumn(name = "registro_acopio_id", updatable = false)
    private RegistroAcopio registroAcopio;

    @Column(name = "litros_originales", nullable = false, updatable = false, precision = 8, scale = 2)
    private BigDecimal litrosOriginales;

    @Column(name = "litros_liquidados", nullable = false, updatable = false, precision = 8, scale = 2)
    private BigDecimal litrosLiquidados;

    @Column(name = "precio_base", nullable = false, updatable = false, precision = 6, scale = 3)
    private BigDecimal precioBase;

    // Ultima correccion valida usada al liquidar; null si se liquido el original.
    @ManyToOne
    @JoinColumn(name = "correccion_aplicada_id", updatable = false)
    private CorreccionRegistro correccionAplicada;

    @Column(name = "creado_en", nullable = false, updatable = false)
    private LocalDateTime creadoEn = LocalDateTime.now();

    public LineaPago() {}

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public Pago getPago() { return pago; }
    public void setPago(Pago pago) { this.pago = pago; }
    public RegistroAcopio getRegistroAcopio() { return registroAcopio; }
    public void setRegistroAcopio(RegistroAcopio registroAcopio) { this.registroAcopio = registroAcopio; }
    public BigDecimal getLitrosOriginales() { return litrosOriginales; }
    public void setLitrosOriginales(BigDecimal litrosOriginales) { this.litrosOriginales = litrosOriginales; }
    public BigDecimal getLitrosLiquidados() { return litrosLiquidados; }
    public void setLitrosLiquidados(BigDecimal litrosLiquidados) { this.litrosLiquidados = litrosLiquidados; }
    public BigDecimal getPrecioBase() { return precioBase; }
    public void setPrecioBase(BigDecimal precioBase) { this.precioBase = precioBase; }
    public CorreccionRegistro getCorreccionAplicada() { return correccionAplicada; }
    public void setCorreccionAplicada(CorreccionRegistro correccionAplicada) { this.correccionAplicada = correccionAplicada; }
    public LocalDateTime getCreadoEn() { return creadoEn; }
    public void setCreadoEn(LocalDateTime creadoEn) { this.creadoEn = creadoEn; }
}
