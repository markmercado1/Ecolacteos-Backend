package com.planta.acopio.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

// Raiz de agregado. mejora #4 (CORE_DESIGN.md §6): trazabilidad exacta hacia
// los RegistroAcopio liquidados, via tabla puente pago_registro_acopio.
@Entity
@Table(name = "pago", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"proveedor_id", "semana_inicio"})
})
public class Pago {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "proveedor_id")
    private Proveedor proveedor;

    @Column(name = "semana_inicio", nullable = false)
    private LocalDate semanaInicio;

    @Column(name = "semana_fin", nullable = false)
    private LocalDate semanaFin;

    @Column(name = "litros_totales", nullable = false, precision = 9, scale = 2)
    private BigDecimal litrosTotales;

    @Column(name = "precio_litro", nullable = false, precision = 6, scale = 3)
    private BigDecimal precioLitro;

    // Columna generada por PostgreSQL (STORED) -- solo lectura desde JPA.
    @Column(insertable = false, updatable = false, precision = 10, scale = 2)
    private BigDecimal total;

    @Column(name = "comprobante_generado", nullable = false)
    private Boolean comprobanteGenerado = false;

    @Column(name = "creado_en", nullable = false, updatable = false)
    private LocalDateTime creadoEn = LocalDateTime.now();

    @ManyToMany
    @JoinTable(
        name = "pago_registro_acopio",
        joinColumns = @JoinColumn(name = "pago_id"),
        inverseJoinColumns = @JoinColumn(name = "registro_acopio_id")
    )
    private Set<RegistroAcopio> registrosLiquidados = new HashSet<>();

    public Pago() {}

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public Proveedor getProveedor() { return proveedor; }
    public void setProveedor(Proveedor proveedor) { this.proveedor = proveedor; }
    public LocalDate getSemanaInicio() { return semanaInicio; }
    public void setSemanaInicio(LocalDate semanaInicio) { this.semanaInicio = semanaInicio; }
    public LocalDate getSemanaFin() { return semanaFin; }
    public void setSemanaFin(LocalDate semanaFin) { this.semanaFin = semanaFin; }
    public BigDecimal getLitrosTotales() { return litrosTotales; }
    public void setLitrosTotales(BigDecimal litrosTotales) { this.litrosTotales = litrosTotales; }
    public BigDecimal getPrecioLitro() { return precioLitro; }
    public void setPrecioLitro(BigDecimal precioLitro) { this.precioLitro = precioLitro; }
    public BigDecimal getTotal() { return total; }
    public Boolean getComprobanteGenerado() { return comprobanteGenerado; }
    public void setComprobanteGenerado(Boolean comprobanteGenerado) { this.comprobanteGenerado = comprobanteGenerado; }
    public LocalDateTime getCreadoEn() { return creadoEn; }
    public void setCreadoEn(LocalDateTime creadoEn) { this.creadoEn = creadoEn; }
    public Set<RegistroAcopio> getRegistrosLiquidados() { return registrosLiquidados; }
    public void setRegistrosLiquidados(Set<RegistroAcopio> registrosLiquidados) { this.registrosLiquidados = registrosLiquidados; }
}
