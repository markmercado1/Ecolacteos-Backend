package com.planta.acopio.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

// V42: proveedor al que se compran insumos (o leche). NO es el proveedor de leche del acopio (entidad Proveedor: ganadero
// con DNI, zona, QR y rutas): son modelos distintos a proposito.
@Entity
@Table(name = "proveedor_compra")
public class ProveedorCompra {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false, length = 150)
    private String nombre;

    @Column(nullable = false, unique = true, length = 11)
    private String ruc;

    @Column(length = 20)
    private String telefono;

    @Column(length = 100)
    private String contacto;

    @Column(nullable = false)
    private Boolean activo = true;

    @Column(name = "creado_en", nullable = false, updatable = false)
    private LocalDateTime creadoEn = LocalDateTime.now();

    @ManyToMany
    @JoinTable(name = "proveedor_compra_categoria",
            joinColumns = @JoinColumn(name = "proveedor_id"), inverseJoinColumns = @JoinColumn(name = "categoria_id"))
    private Set<CategoriaProveedor> categorias = new LinkedHashSet<>();

    // Insumos que suele proveer: solo para sugerirlos al registrar un ingreso.
    @ManyToMany
    @JoinTable(name = "proveedor_compra_insumo",
            joinColumns = @JoinColumn(name = "proveedor_id"), inverseJoinColumns = @JoinColumn(name = "insumo_id"))
    private Set<Insumo> insumosHabituales = new LinkedHashSet<>();

    public ProveedorCompra() {}

    public UUID getId() { return id; }
    public String getNombre() { return nombre; }
    public void setNombre(String nombre) { this.nombre = nombre; }
    public String getRuc() { return ruc; }
    public void setRuc(String ruc) { this.ruc = ruc; }
    public String getTelefono() { return telefono; }
    public void setTelefono(String telefono) { this.telefono = telefono; }
    public String getContacto() { return contacto; }
    public void setContacto(String contacto) { this.contacto = contacto; }
    public Boolean getActivo() { return activo; }
    public void setActivo(Boolean activo) { this.activo = activo; }
    public LocalDateTime getCreadoEn() { return creadoEn; }
    public Set<CategoriaProveedor> getCategorias() { return categorias; }
    public Set<Insumo> getInsumosHabituales() { return insumosHabituales; }
}
