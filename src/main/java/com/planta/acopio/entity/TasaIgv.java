package com.planta.acopio.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

// Fase G (V32): tasa del IGV con vigencia (porcentaje, p. ej. 18.00). Append-only: una tasa nueva abre otra vigencia.
@Entity
@Table(name = "tasa_igv")
public class TasaIgv {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal tasa;

    @Column(name = "vigente_desde", nullable = false, unique = true)
    private LocalDate vigenteDesde;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String motivo;

    @ManyToOne
    @JoinColumn(name = "usuario_id")
    private Usuario usuario;

    @Column(name = "creado_en", nullable = false, updatable = false)
    private LocalDateTime creadoEn = LocalDateTime.now();

    public TasaIgv() {}

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public BigDecimal getTasa() { return tasa; }
    public void setTasa(BigDecimal tasa) { this.tasa = tasa; }
    public LocalDate getVigenteDesde() { return vigenteDesde; }
    public void setVigenteDesde(LocalDate vigenteDesde) { this.vigenteDesde = vigenteDesde; }
    public String getMotivo() { return motivo; }
    public void setMotivo(String motivo) { this.motivo = motivo; }
    public Usuario getUsuario() { return usuario; }
    public void setUsuario(Usuario usuario) { this.usuario = usuario; }
    public LocalDateTime getCreadoEn() { return creadoEn; }
}
