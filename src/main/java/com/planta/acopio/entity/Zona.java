package com.planta.acopio.entity;

import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "zona")
public class Zona {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false, length = 80)
    private String nombre;

    @Column(nullable = false)
    private Boolean activa = true;

    public Zona() {}

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getNombre() { return nombre; }
    public void setNombre(String nombre) { this.nombre = nombre; }
    public Boolean getActiva() { return activa; }
    public void setActiva(Boolean activa) { this.activa = activa; }
}
