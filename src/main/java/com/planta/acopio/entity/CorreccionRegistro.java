package com.planta.acopio.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

// Solo CREATE, nunca UPDATE/DELETE (RNF-03/05: auditoria de disputas).
@Entity
@Table(name = "correccion_registro")
public class CorreccionRegistro {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "registro_acopio_id")
    private RegistroAcopio registroAcopio;

    @ManyToOne(optional = false)
    @JoinColumn(name = "usuario_id")
    private Usuario usuario;

    @Column(name = "litros_anterior", nullable = false, precision = 8, scale = 2)
    private BigDecimal litrosAnterior;

    @Column(name = "litros_corregido", nullable = false, precision = 8, scale = 2)
    private BigDecimal litrosCorregido;

    @Column(columnDefinition = "TEXT")
    private String motivo;

    @Column(name = "creado_en", nullable = false, updatable = false)
    private LocalDateTime creadoEn = LocalDateTime.now();

    public CorreccionRegistro() {}

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public RegistroAcopio getRegistroAcopio() { return registroAcopio; }
    public void setRegistroAcopio(RegistroAcopio registroAcopio) { this.registroAcopio = registroAcopio; }
    public Usuario getUsuario() { return usuario; }
    public void setUsuario(Usuario usuario) { this.usuario = usuario; }
    public BigDecimal getLitrosAnterior() { return litrosAnterior; }
    public void setLitrosAnterior(BigDecimal litrosAnterior) { this.litrosAnterior = litrosAnterior; }
    public BigDecimal getLitrosCorregido() { return litrosCorregido; }
    public void setLitrosCorregido(BigDecimal litrosCorregido) { this.litrosCorregido = litrosCorregido; }
    public String getMotivo() { return motivo; }
    public void setMotivo(String motivo) { this.motivo = motivo; }
    public LocalDateTime getCreadoEn() { return creadoEn; }
    public void setCreadoEn(LocalDateTime creadoEn) { this.creadoEn = creadoEn; }
}
