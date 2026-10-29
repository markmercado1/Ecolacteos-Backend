package com.planta.acopio.entity;

import jakarta.persistence.*;
import java.util.UUID;

// V42: categoria de un proveedor de compra (LECHE, OTROS_INSUMOS...). Catalogo pequeno y abierto.
@Entity
@Table(name = "categoria_proveedor")
public class CategoriaProveedor {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false, unique = true, length = 30)
    private String codigo;

    @Column(nullable = false, length = 60)
    private String nombre;

    @Column(nullable = false)
    private Boolean activo = true;

    public CategoriaProveedor() {}

    public UUID getId() { return id; }
    public String getCodigo() { return codigo; }
    public void setCodigo(String codigo) { this.codigo = codigo; }
    public String getNombre() { return nombre; }
    public void setNombre(String nombre) { this.nombre = nombre; }
    public Boolean getActivo() { return activo; }
    public void setActivo(Boolean activo) { this.activo = activo; }
}
