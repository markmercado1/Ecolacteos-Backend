package com.planta.acopio.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

// Escrita unicamente por AnomaliaService al sincronizar -- RF-INN-03.
@Entity
@Table(name = "alerta_anomalia")
public class AlertaAnomalia {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "registro_acopio_id")
    private RegistroAcopio registroAcopio;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 25)
    private TipoAlerta tipo;

    @Column(name = "z_score", precision = 6, scale = 3)
    private BigDecimal zScore;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Severidad severidad;

    @Column(name = "creado_en", nullable = false, updatable = false)
    private LocalDateTime creadoEn = LocalDateTime.now();

    public AlertaAnomalia() {}

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public RegistroAcopio getRegistroAcopio() { return registroAcopio; }
    public void setRegistroAcopio(RegistroAcopio registroAcopio) { this.registroAcopio = registroAcopio; }
    public TipoAlerta getTipo() { return tipo; }
    public void setTipo(TipoAlerta tipo) { this.tipo = tipo; }
    public BigDecimal getZScore() { return zScore; }
    public void setZScore(BigDecimal zScore) { this.zScore = zScore; }
    public Severidad getSeveridad() { return severidad; }
    public void setSeveridad(Severidad severidad) { this.severidad = severidad; }
    public LocalDateTime getCreadoEn() { return creadoEn; }
    public void setCreadoEn(LocalDateTime creadoEn) { this.creadoEn = creadoEn; }
}
