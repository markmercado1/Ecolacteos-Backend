package com.planta.acopio.entity;

import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "rol")
public class Rol {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false, unique = true, length = 30)
    private String nombre;

    public Rol() {}

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getNombre() { return nombre; }
    public void setNombre(String nombre) { this.nombre = nombre; }
}
