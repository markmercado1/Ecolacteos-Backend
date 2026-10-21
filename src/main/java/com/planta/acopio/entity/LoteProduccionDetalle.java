package com.planta.acopio.entity;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

// Fase 3 (Produccion con varias presentaciones de salida, V12__produccion_multi_presentacion.sql): un lote
// corresponde a UN producto elaborado pero puede generar VARIAS presentaciones de salida (ej. 20 botellas de
// 2 L + 40 de 1 L + 30 de 500 ml, todas del mismo lote de Yogurt de fresa, un solo consumo de leche). Cada
// fila es una salida real y positiva -- nunca se persiste una linea de cantidad cero (esa ausencia se
// representa con CERO filas de detalle para el lote, no con una fila en cero: ver
// LoteProduccionService.crear() para el caso "produccion sin ninguna unidad obtenida"). UNIQUE(lote,
// presentacion): una presentacion no puede repetirse en el mismo lote (evita lineas duplicadas).
@Entity
@Table(name = "lote_produccion_detalle")
public class LoteProduccionDetalle {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "lote_id")
    private LoteProduccion lote;

    @ManyToOne(optional = false)
    @JoinColumn(name = "presentacion_id")
    private Presentacion presentacion;

    @Column(name = "cantidad_obtenida", nullable = false)
    private Integer cantidadObtenida;

    // Fase 1 (V21): vencimiento registrado por una persona para esta salida (solo productos con control).
    // Nunca se calcula con una duracion generica. La original se conserva al corregir; de la ultima
    // correccion se guarda quien, cuando y por que.
    @Column(name = "fecha_vencimiento")
    private LocalDate fechaVencimiento;

    @Column(name = "fecha_vencimiento_original")
    private LocalDate fechaVencimientoOriginal;

    @Column(name = "vencimiento_corregido_por")
    private UUID vencimientoCorregidoPor;

    @Column(name = "vencimiento_corregido_en")
    private LocalDateTime vencimientoCorregidoEn;

    @Column(name = "vencimiento_motivo_correccion", length = 300)
    private String vencimientoMotivoCorreccion;

    public LoteProduccionDetalle() {}

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public LoteProduccion getLote() { return lote; }
    public void setLote(LoteProduccion lote) { this.lote = lote; }
    public Presentacion getPresentacion() { return presentacion; }
    public void setPresentacion(Presentacion presentacion) { this.presentacion = presentacion; }
    public Integer getCantidadObtenida() { return cantidadObtenida; }
    public void setCantidadObtenida(Integer cantidadObtenida) { this.cantidadObtenida = cantidadObtenida; }
    public LocalDate getFechaVencimiento() { return fechaVencimiento; }
    public void setFechaVencimiento(LocalDate fechaVencimiento) { this.fechaVencimiento = fechaVencimiento; }
    public LocalDate getFechaVencimientoOriginal() { return fechaVencimientoOriginal; }
    public void setFechaVencimientoOriginal(LocalDate fechaVencimientoOriginal) { this.fechaVencimientoOriginal = fechaVencimientoOriginal; }
    public UUID getVencimientoCorregidoPor() { return vencimientoCorregidoPor; }
    public void setVencimientoCorregidoPor(UUID vencimientoCorregidoPor) { this.vencimientoCorregidoPor = vencimientoCorregidoPor; }
    public LocalDateTime getVencimientoCorregidoEn() { return vencimientoCorregidoEn; }
    public void setVencimientoCorregidoEn(LocalDateTime vencimientoCorregidoEn) { this.vencimientoCorregidoEn = vencimientoCorregidoEn; }
    public String getVencimientoMotivoCorreccion() { return vencimientoMotivoCorreccion; }
    public void setVencimientoMotivoCorreccion(String vencimientoMotivoCorreccion) { this.vencimientoMotivoCorreccion = vencimientoMotivoCorreccion; }
}
