package com.planta.acopio.entity;

import jakarta.persistence.*;

// Tipos de precio administrables (V37). El codigo es el valor de TipoClienteVenta que ya usan precios y ventas; aqui solo se decide si el
// tipo se ofrece para operaciones NUEVAS. Los registros historicos no cambian.
@Entity
@Table(name = "tipo_precio")
public class TipoPrecio {

    @Id
    @Column(length = 15)
    private String codigo;

    @Column(nullable = false, length = 60)
    private String nombre;

    @Column(nullable = false, length = 300)
    private String descripcion;

    @Column(nullable = false)
    private boolean activo = true;

    @Column(nullable = false, updatable = false)
    private boolean protegido;

    public TipoPrecio() {}

    public String getCodigo() { return codigo; }
    public String getNombre() { return nombre; }
    public String getDescripcion() { return descripcion; }
    public boolean isActivo() { return activo; }
    public void setActivo(boolean activo) { this.activo = activo; }
    public boolean isProtegido() { return protegido; }
}
