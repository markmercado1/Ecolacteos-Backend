package com.planta.acopio.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

// Receta/formulacion de produccion (V18__receta_produccion.sql): como fabricar UN producto, con salida en
// UNA de sus presentaciones, a partir de litrosBase de leche. Escalable: todas las cantidades (insumos y
// rendimiento) son "por litrosBase" y el calculo proporcional vive en CalculoReceta. La leche NO es un
// insumo del catalogo (tiene su propio libro mayor, V8) -- es la base de la receta. producto/presentacion no
// se editan despues de crear (ver RecetaService.actualizar()).
@Entity
@Table(name = "receta")
public class Receta {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false, length = 100)
    private String nombre;

    @ManyToOne(optional = false)
    @JoinColumn(name = "producto_id")
    private Producto producto;

    @ManyToOne(optional = false)
    @JoinColumn(name = "presentacion_id")
    private Presentacion presentacion;

    @Column(name = "litros_base", nullable = false, precision = 9, scale = 2)
    private BigDecimal litrosBase;

    // Cantidad de la presentacion de salida esperada por cada litrosBase (ej. 1 pieza por 100 L).
    @Column(name = "rendimiento_base", nullable = false, precision = 12, scale = 3)
    private BigDecimal rendimientoBase;

    @Column(nullable = false)
    private Boolean activo = true;

    @Column(name = "creado_en", nullable = false, updatable = false)
    private LocalDateTime creadoEn = LocalDateTime.now();

    @Column(name = "actualizado_en")
    private LocalDateTime actualizadoEn;

    // orphanRemoval: editar la receta reemplaza su lista de insumos completa (mismo criterio que el informe
    // diario al editar); los lotes ya planificados no dependen de estas filas (guardan su propio snapshot en
    // lote_produccion_insumo).
    @OneToMany(mappedBy = "receta", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id")
    private List<RecetaInsumo> insumos = new ArrayList<>();

    public Receta() {}

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getNombre() { return nombre; }
    public void setNombre(String nombre) { this.nombre = nombre; }
    public Producto getProducto() { return producto; }
    public void setProducto(Producto producto) { this.producto = producto; }
    public Presentacion getPresentacion() { return presentacion; }
    public void setPresentacion(Presentacion presentacion) { this.presentacion = presentacion; }
    public BigDecimal getLitrosBase() { return litrosBase; }
    public void setLitrosBase(BigDecimal litrosBase) { this.litrosBase = litrosBase; }
    public BigDecimal getRendimientoBase() { return rendimientoBase; }
    public void setRendimientoBase(BigDecimal rendimientoBase) { this.rendimientoBase = rendimientoBase; }
    public Boolean getActivo() { return activo; }
    public void setActivo(Boolean activo) { this.activo = activo; }
    public LocalDateTime getCreadoEn() { return creadoEn; }
    public void setCreadoEn(LocalDateTime creadoEn) { this.creadoEn = creadoEn; }
    public LocalDateTime getActualizadoEn() { return actualizadoEn; }
    public void setActualizadoEn(LocalDateTime actualizadoEn) { this.actualizadoEn = actualizadoEn; }
    public List<RecetaInsumo> getInsumos() { return insumos; }
    public void setInsumos(List<RecetaInsumo> insumos) { this.insumos = insumos; }
}
