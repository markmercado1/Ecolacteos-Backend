package com.planta.acopio.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

// PK natural (String), no UUID -- ver CORE_DESIGN.md §8.
@Entity
@Table(name = "configuracion_sistema")
public class ConfiguracionSistema {

    @Id
    @Column(length = 60)
    private String clave;

    @Column(nullable = false, length = 255)
    private String valor;

    @Column(length = 255)
    private String descripcion;

    @Column(name = "actualizado_en", nullable = false)
    private LocalDateTime actualizadoEn = LocalDateTime.now();

    // Fase 4 (V23): quien cambio el valor por ultima vez (auditoria). null = valor inicial de la migracion.
    @Column(name = "actualizado_por")
    private java.util.UUID actualizadoPor;

    public ConfiguracionSistema() {}

    public String getClave() { return clave; }
    public void setClave(String clave) { this.clave = clave; }
    public String getValor() { return valor; }
    public void setValor(String valor) { this.valor = valor; }
    public String getDescripcion() { return descripcion; }
    public void setDescripcion(String descripcion) { this.descripcion = descripcion; }
    public java.util.UUID getActualizadoPor() { return actualizadoPor; }
    public void setActualizadoPor(java.util.UUID actualizadoPor) { this.actualizadoPor = actualizadoPor; }
    public LocalDateTime getActualizadoEn() { return actualizadoEn; }
    public void setActualizadoEn(LocalDateTime actualizadoEn) { this.actualizadoEn = actualizadoEn; }
}
