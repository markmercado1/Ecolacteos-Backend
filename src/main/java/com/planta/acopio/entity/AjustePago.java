package com.planta.acopio.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

// Fase 3, decision de negocio 1 (opcion C, confirmada): una CorreccionRegistro
// anexada sobre un registro_acopio ya liquidado NO modifica Pago ni
// RegistroAcopio (ambos siguen inmutables) -- genera este ajuste separado.
// precio_litro es siempre el del Pago original (decision confirmada). La
// liquidacion es manual (ADMIN via AjustePagoService.liquidar), nunca se
// pliega automaticamente en un Pago nuevo.
@Entity
@Table(name = "ajuste_pago")
public class AjustePago {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "pago_id")
    private Pago pago;

    @ManyToOne(optional = false)
    @JoinColumn(name = "registro_acopio_id")
    private RegistroAcopio registroAcopio;

    @ManyToOne(optional = false)
    @JoinColumn(name = "correccion_registro_id")
    private CorreccionRegistro correccionRegistro;

    @Column(name = "litros_diferencia", nullable = false, precision = 8, scale = 2)
    private BigDecimal litrosDiferencia;

    @Column(name = "precio_litro", nullable = false, precision = 6, scale = 3)
    private BigDecimal precioLitro;

    // Fase B: litros que el pago ya tenia en cuenta para esta entrega justo antes de este ajuste (base del siguiente).
    @Column(name = "litros_contabilizados_antes", precision = 8, scale = 2)
    private BigDecimal litrosContabilizadosAntes;

    // Fase B: precio congelado al liquidar el ajuste; mientras esta PENDIENTE es null y el ajuste se valora al precio
    // efectivo del pago (precio_litro es el de cuando se creo, solo informativo).
    @Column(name = "precio_liquidado", precision = 6, scale = 3)
    private BigDecimal precioLitroLiquidado;

    // Columna generada por PostgreSQL (STORED) -- solo lectura desde JPA.
    @Column(insertable = false, updatable = false, precision = 10, scale = 2)
    private BigDecimal monto;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private EstadoAjustePago estado = EstadoAjustePago.PENDIENTE;

    @ManyToOne
    @JoinColumn(name = "pago_liquidacion_id")
    private Pago pagoLiquidacion;

    @Column(name = "creado_en", nullable = false, updatable = false)
    private LocalDateTime creadoEn = LocalDateTime.now();

    public AjustePago() {}

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public Pago getPago() { return pago; }
    public void setPago(Pago pago) { this.pago = pago; }
    public RegistroAcopio getRegistroAcopio() { return registroAcopio; }
    public void setRegistroAcopio(RegistroAcopio registroAcopio) { this.registroAcopio = registroAcopio; }
    public CorreccionRegistro getCorreccionRegistro() { return correccionRegistro; }
    public void setCorreccionRegistro(CorreccionRegistro correccionRegistro) { this.correccionRegistro = correccionRegistro; }
    public BigDecimal getLitrosDiferencia() { return litrosDiferencia; }
    public void setLitrosDiferencia(BigDecimal litrosDiferencia) { this.litrosDiferencia = litrosDiferencia; }
    public BigDecimal getPrecioLitro() { return precioLitro; }
    public void setPrecioLitro(BigDecimal precioLitro) { this.precioLitro = precioLitro; }
    public BigDecimal getLitrosContabilizadosAntes() { return litrosContabilizadosAntes; }
    public void setLitrosContabilizadosAntes(BigDecimal litrosContabilizadosAntes) { this.litrosContabilizadosAntes = litrosContabilizadosAntes; }
    public BigDecimal getPrecioLitroLiquidado() { return precioLitroLiquidado; }
    public void setPrecioLitroLiquidado(BigDecimal precioLitroLiquidado) { this.precioLitroLiquidado = precioLitroLiquidado; }
    public BigDecimal getMonto() { return monto; }
    public EstadoAjustePago getEstado() { return estado; }
    public void setEstado(EstadoAjustePago estado) { this.estado = estado; }
    public Pago getPagoLiquidacion() { return pagoLiquidacion; }
    public void setPagoLiquidacion(Pago pagoLiquidacion) { this.pagoLiquidacion = pagoLiquidacion; }
    public LocalDateTime getCreadoEn() { return creadoEn; }
    public void setCreadoEn(LocalDateTime creadoEn) { this.creadoEn = creadoEn; }
}
