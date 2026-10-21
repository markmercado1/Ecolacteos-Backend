package com.planta.acopio.entity;

import jakarta.persistence.*;
import java.util.UUID;

// Fase 1 (Producto + Presentacion): catalogo administrable desde la web, mismo patron que
// Zona/Unidad/MotivoObservacion. Un producto pertenece a exactamente una familia (Queso, Yogurt, Leche,
// Mantequilla, Dulce de leche...); el conjunto de familias NO es un enum fijo -- el usuario autorizado crea
// las que necesite desde /admin/familias, sin tocar codigo ni SQL.
@Entity
@Table(name = "familia")
public class Familia {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false, unique = true, length = 60)
    private String nombre;

    @Column(nullable = false)
    private Boolean activo = true;

    public Familia() {}

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getNombre() { return nombre; }
    public void setNombre(String nombre) { this.nombre = nombre; }
    public Boolean getActivo() { return activo; }
    public void setActivo(Boolean activo) { this.activo = activo; }
}
