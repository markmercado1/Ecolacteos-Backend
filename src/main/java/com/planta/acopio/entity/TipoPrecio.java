package com.planta.acopio.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

// Tipos de precio dinamicos (V37, V39). El codigo es el identificador estable que referencian precios, ventas y clientes: nunca cambia. El
// nombre y la descripcion se pueden editar sin alterar ninguna referencia historica. `activo` solo decide si se ofrece en operaciones NUEVAS.
@Entity
@Table(name = "tipo_precio")
public class TipoPrecio {

    /** Precio del catalogo publico, las reservas y los retiros: el sistema lo referencia por este codigo. */
    public static final String PUBLICO = "PUBLICO";

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

    @Enumerated(EnumType.STRING)
    @Column(name = "regla_comprador", nullable = false, length = 20, updatable = false)
    private ReglaComprador reglaComprador = ReglaComprador.CUALQUIERA;

    @Column(name = "creado_en", nullable = false, updatable = false)
    private LocalDateTime creadoEn = LocalDateTime.now();

    public TipoPrecio() {}

    public String getCodigo() { return codigo; }
    public void setCodigo(String codigo) { this.codigo = codigo; }
    public String getNombre() { return nombre; }
    public void setNombre(String nombre) { this.nombre = nombre; }
    public String getDescripcion() { return descripcion; }
    public void setDescripcion(String descripcion) { this.descripcion = descripcion; }
    public boolean isActivo() { return activo; }
    public void setActivo(boolean activo) { this.activo = activo; }
    public boolean isProtegido() { return protegido; }
    public ReglaComprador getReglaComprador() { return reglaComprador; }
    public void setReglaComprador(ReglaComprador reglaComprador) { this.reglaComprador = reglaComprador; }
    public LocalDateTime getCreadoEn() { return creadoEn; }
}
