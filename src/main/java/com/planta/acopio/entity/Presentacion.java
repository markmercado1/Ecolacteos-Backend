package com.planta.acopio.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.UUID;

// Fase 1 (Producto + Presentacion): como se cuenta/almacena/vende un producto (ej. "Botella 1 L", "Pieza").
// Pertenece a un producto. Todavia NO es el destino de precio_queso/movimiento_inventario/venta/
// lote_produccion en esta fase -- esa reconexion es una ampliacion posterior (decision aprobada, fuera de
// alcance de Fase 1); aqui existe solo como catalogo administrable dentro de la ficha del producto.
@Entity
@Table(name = "presentacion")
public class Presentacion {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "producto_id")
    private Producto producto;

    @Column(nullable = false, length = 60)
    private String nombre;

    // Descriptivo, nunca inventado: una "Pieza" sin peso/volumen declarado deja ambos en null.
    @Column(name = "contenido_cantidad", precision = 10, scale = 3)
    private BigDecimal contenidoCantidad;

    @Enumerated(EnumType.STRING)
    @Column(name = "contenido_unidad", length = 10)
    private UnidadContenido contenidoUnidad;

    // Como se cuenta la unidad discreta (botella, frasco, pieza, envase...): vocabulario abierto.
    @Column(name = "unidad_conteo", nullable = false, length = 30)
    private String unidadConteo;

    @Column(nullable = false)
    private Boolean activo = true;

    // Nombre del archivo de la foto en el almacenamiento del catalogo (V20); null = sin foto propia (el
    // catalogo usa la del producto).
    @Column(name = "foto_archivo", length = 80)
    private String fotoArchivo;

    // Fase 1 (V21): stock del libro al activar el control de vencimiento del producto = existencias "sin
    // trazabilidad" (sin lote ni fecha atribuibles). NULL = presentacion sin control de vencimiento.
    @Column(name = "saldo_sin_trazabilidad_inicial")
    private Integer saldoSinTrazabilidadInicial;

    public Presentacion() {}

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public Producto getProducto() { return producto; }
    public void setProducto(Producto producto) { this.producto = producto; }
    public String getNombre() { return nombre; }
    public void setNombre(String nombre) { this.nombre = nombre; }
    public BigDecimal getContenidoCantidad() { return contenidoCantidad; }
    public void setContenidoCantidad(BigDecimal contenidoCantidad) { this.contenidoCantidad = contenidoCantidad; }
    public UnidadContenido getContenidoUnidad() { return contenidoUnidad; }
    public void setContenidoUnidad(UnidadContenido contenidoUnidad) { this.contenidoUnidad = contenidoUnidad; }
    public String getUnidadConteo() { return unidadConteo; }
    public void setUnidadConteo(String unidadConteo) { this.unidadConteo = unidadConteo; }
    public Boolean getActivo() { return activo; }
    public void setActivo(Boolean activo) { this.activo = activo; }
    public String getFotoArchivo() { return fotoArchivo; }
    public void setFotoArchivo(String fotoArchivo) { this.fotoArchivo = fotoArchivo; }
    public Integer getSaldoSinTrazabilidadInicial() { return saldoSinTrazabilidadInicial; }
    public void setSaldoSinTrazabilidadInicial(Integer saldoSinTrazabilidadInicial) { this.saldoSinTrazabilidadInicial = saldoSinTrazabilidadInicial; }
}
