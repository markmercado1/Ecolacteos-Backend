package com.planta.acopio.entity;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

// Fase 2 (V22): reclasifica `cantidad` unidades del saldo "sin trazabilidad" de una presentacion controlada a
// un lote con la fecha de vencimiento de la etiqueta fisica. NO escribe en el libro (el stock total no
// cambia): es solo un cambio de "cajon". Append-only: nunca se edita ni se borra.
@Entity
@Table(name = "regularizacion_trazabilidad")
public class RegularizacionTrazabilidad {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "presentacion_id")
    private Presentacion presentacion;

    @Column(nullable = false)
    private Integer cantidad;

    @Column(name = "fecha_vencimiento", nullable = false)
    private LocalDate fechaVencimiento;

    @Column(nullable = false, length = 300)
    private String motivo;

    @ManyToOne(optional = false)
    @JoinColumn(name = "usuario_id")
    private Usuario usuario;

    @Column(name = "creado_en", nullable = false, updatable = false)
    private LocalDateTime creadoEn = LocalDateTime.now();

    public RegularizacionTrazabilidad() {}

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public Presentacion getPresentacion() { return presentacion; }
    public void setPresentacion(Presentacion presentacion) { this.presentacion = presentacion; }
    public Integer getCantidad() { return cantidad; }
    public void setCantidad(Integer cantidad) { this.cantidad = cantidad; }
    public LocalDate getFechaVencimiento() { return fechaVencimiento; }
    public void setFechaVencimiento(LocalDate fechaVencimiento) { this.fechaVencimiento = fechaVencimiento; }
    public String getMotivo() { return motivo; }
    public void setMotivo(String motivo) { this.motivo = motivo; }
    public Usuario getUsuario() { return usuario; }
    public void setUsuario(Usuario usuario) { this.usuario = usuario; }
    public LocalDateTime getCreadoEn() { return creadoEn; }
    public void setCreadoEn(LocalDateTime creadoEn) { this.creadoEn = creadoEn; }
}
