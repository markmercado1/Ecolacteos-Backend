package com.planta.acopio.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;

// Inventario: accion "Ajustar stock" (correccion manual por conteo fisico -- perdidas, roturas, errores de
// conteo). Solo CREATE, nunca UPDATE/DELETE (mismo criterio que CorreccionRegistro): cantidadAnterior es
// siempre la que tenia el sistema al momento del ajuste, nunca un valor recalculado despues. Nunca toca
// leche ni el lote de produccion que genero el stock original -- ver AjusteInventarioService.
@Entity
@Table(name = "ajuste_inventario")
public class AjusteInventario {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "presentacion_id")
    private Presentacion presentacion;

    @ManyToOne(optional = false)
    @JoinColumn(name = "usuario_id")
    private Usuario usuario;

    @Column(name = "cantidad_anterior", nullable = false)
    private Integer cantidadAnterior;

    @Column(name = "cantidad_corregida", nullable = false)
    private Integer cantidadCorregida;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String motivo;

    @Column(name = "creado_en", nullable = false, updatable = false)
    private LocalDateTime creadoEn = LocalDateTime.now();

    public AjusteInventario() {}

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public Presentacion getPresentacion() { return presentacion; }
    public void setPresentacion(Presentacion presentacion) { this.presentacion = presentacion; }
    public Usuario getUsuario() { return usuario; }
    public void setUsuario(Usuario usuario) { this.usuario = usuario; }
    public Integer getCantidadAnterior() { return cantidadAnterior; }
    public void setCantidadAnterior(Integer cantidadAnterior) { this.cantidadAnterior = cantidadAnterior; }
    public Integer getCantidadCorregida() { return cantidadCorregida; }
    public void setCantidadCorregida(Integer cantidadCorregida) { this.cantidadCorregida = cantidadCorregida; }
    public String getMotivo() { return motivo; }
    public void setMotivo(String motivo) { this.motivo = motivo; }
    public LocalDateTime getCreadoEn() { return creadoEn; }
    public void setCreadoEn(LocalDateTime creadoEn) { this.creadoEn = creadoEn; }
}
