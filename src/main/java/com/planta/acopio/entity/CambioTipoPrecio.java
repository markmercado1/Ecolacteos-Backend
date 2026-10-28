package com.planta.acopio.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;

// Activacion o desactivacion de un tipo de precio (V37). Append-only: quien, cuando y por que.
@Entity
@Table(name = "cambio_tipo_precio")
public class CambioTipoPrecio {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false, updatable = false, length = 15)
    private String codigo;

    @Column(nullable = false, updatable = false)
    private boolean activo;

    @Column(nullable = false, updatable = false, columnDefinition = "TEXT")
    private String motivo;

    @ManyToOne
    @JoinColumn(name = "usuario_id", updatable = false)
    private Usuario usuario;

    @Column(name = "creado_en", nullable = false, updatable = false)
    private LocalDateTime creadoEn = LocalDateTime.now();

    public CambioTipoPrecio() {}

    public UUID getId() { return id; }
    public String getCodigo() { return codigo; }
    public void setCodigo(String codigo) { this.codigo = codigo; }
    public boolean isActivo() { return activo; }
    public void setActivo(boolean activo) { this.activo = activo; }
    public String getMotivo() { return motivo; }
    public void setMotivo(String motivo) { this.motivo = motivo; }
    public Usuario getUsuario() { return usuario; }
    public void setUsuario(Usuario usuario) { this.usuario = usuario; }
    public LocalDateTime getCreadoEn() { return creadoEn; }
}
