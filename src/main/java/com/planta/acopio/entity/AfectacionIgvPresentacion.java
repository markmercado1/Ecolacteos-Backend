package com.planta.acopio.entity;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

// Fase G (V32): afectacion al IGV de una presentacion desde una fecha. Append-only (como los precios): un cambio abre otra
// vigencia y queda con su motivo y usuario. Sin filas vigentes = SIN_DEFINIR.
@Entity
@Table(name = "afectacion_igv_presentacion")
public class AfectacionIgvPresentacion {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "presentacion_id")
    private Presentacion presentacion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private AfectacionIgv afectacion;

    @Column(name = "vigente_desde", nullable = false)
    private LocalDate vigenteDesde;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String motivo;

    @ManyToOne
    @JoinColumn(name = "usuario_id")
    private Usuario usuario;

    @Column(name = "creado_en", nullable = false, updatable = false)
    private LocalDateTime creadoEn = LocalDateTime.now();

    public AfectacionIgvPresentacion() {}

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public Presentacion getPresentacion() { return presentacion; }
    public void setPresentacion(Presentacion presentacion) { this.presentacion = presentacion; }
    public AfectacionIgv getAfectacion() { return afectacion; }
    public void setAfectacion(AfectacionIgv afectacion) { this.afectacion = afectacion; }
    public LocalDate getVigenteDesde() { return vigenteDesde; }
    public void setVigenteDesde(LocalDate vigenteDesde) { this.vigenteDesde = vigenteDesde; }
    public String getMotivo() { return motivo; }
    public void setMotivo(String motivo) { this.motivo = motivo; }
    public Usuario getUsuario() { return usuario; }
    public void setUsuario(Usuario usuario) { this.usuario = usuario; }
    public LocalDateTime getCreadoEn() { return creadoEn; }
}
