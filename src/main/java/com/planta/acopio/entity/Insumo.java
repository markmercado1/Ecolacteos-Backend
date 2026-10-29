package com.planta.acopio.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.UUID;

// Catálogo administrable (mismo patrón que Zona/TipoQueso/MotivoObservacion): insumos consumidos en el
// área de producción de quesos (cuajo, sal, etc.). Abierto a futuro -- agregar un insumo nuevo no requiere
// migración ni cambios de código, solo un alta vía AdminCatalogosController.
@Entity
@Table(name = "insumo")
public class Insumo {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false, unique = true, length = 60)
    private String nombre;

    @Column(name = "unidad_medida", nullable = false, length = 20)
    private String unidadMedida;

    @Column(nullable = false)
    private Boolean activo = true;

    // V17: stock disponible. Desde V18 se descuenta al iniciar una produccion planificada o al registrar un
    // lote directo con insumos (InsumoRepository.descontarStock, atomico y sin negativos).
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal stock = BigDecimal.ZERO;

    // V41: si el insumo fue fusionado en otro (herramienta de duplicados), apunta al que lo absorbio. Un insumo
    // fusionado queda inactivo, con stock 0, y no admite ningun movimiento ni edicion.
    @Column(name = "fusionado_en_id")
    private UUID fusionadoEnId;

    public Insumo() {}

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getNombre() { return nombre; }
    public void setNombre(String nombre) { this.nombre = nombre; }
    public String getUnidadMedida() { return unidadMedida; }
    public void setUnidadMedida(String unidadMedida) { this.unidadMedida = unidadMedida; }
    public Boolean getActivo() { return activo; }
    public void setActivo(Boolean activo) { this.activo = activo; }
    public UUID getFusionadoEnId() { return fusionadoEnId; }
    public void setFusionadoEnId(UUID fusionadoEnId) { this.fusionadoEnId = fusionadoEnId; }
    public boolean estaFusionado() { return fusionadoEnId != null; }
    public BigDecimal getStock() { return stock; }
    public void setStock(BigDecimal stock) { this.stock = stock; }
}
