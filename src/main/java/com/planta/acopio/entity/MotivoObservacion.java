package com.planta.acopio.entity;

import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "motivo_observacion")
public class MotivoObservacion {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false, unique = true, length = 120)
    private String descripcion;

    @Column(nullable = false)
    private Boolean activo = true;

    public MotivoObservacion() {}

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getDescripcion() { return descripcion; }
    public void setDescripcion(String descripcion) { this.descripcion = descripcion; }
    public Boolean getActivo() { return activo; }
    public void setActivo(Boolean activo) { this.activo = activo; }
}
