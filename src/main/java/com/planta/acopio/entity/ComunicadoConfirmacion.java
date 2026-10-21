package com.planta.acopio.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "comunicado_confirmacion")
public class ComunicadoConfirmacion {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "comunicado_id")
    private Comunicado comunicado;

    @ManyToOne(optional = false)
    @JoinColumn(name = "proveedor_id")
    private Proveedor proveedor;

    @ManyToOne(optional = false)
    @JoinColumn(name = "acopiador_id")
    private Usuario acopiador;

    @Column(name = "confirmado_en", nullable = false, updatable = false)
    private LocalDateTime confirmadoEn = LocalDateTime.now();

    public ComunicadoConfirmacion() {}

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public Comunicado getComunicado() { return comunicado; }
    public void setComunicado(Comunicado comunicado) { this.comunicado = comunicado; }
    public Proveedor getProveedor() { return proveedor; }
    public void setProveedor(Proveedor proveedor) { this.proveedor = proveedor; }
    public Usuario getAcopiador() { return acopiador; }
    public void setAcopiador(Usuario acopiador) { this.acopiador = acopiador; }
    public LocalDateTime getConfirmadoEn() { return confirmadoEn; }
    public void setConfirmadoEn(LocalDateTime confirmadoEn) { this.confirmadoEn = confirmadoEn; }
}
