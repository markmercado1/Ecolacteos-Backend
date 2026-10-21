package com.planta.acopio.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import java.math.BigDecimal;

@Embeddable
public class RangoRendimiento {
    @Column(name = "rendimiento_minimo", precision = 16, scale = 4)
    private BigDecimal minimo;

    @Column(name = "rendimiento_maximo", precision = 16, scale = 4)
    private BigDecimal maximo;

    @Enumerated(EnumType.STRING)
    @Column(name = "rendimiento_unidad", length = 6)
    private UnidadRendimiento unidad;

    protected RangoRendimiento() {}

    public RangoRendimiento(BigDecimal minimo, BigDecimal maximo, UnidadRendimiento unidad) {
        this.minimo = minimo;
        this.maximo = maximo;
        this.unidad = unidad;
    }

    public BigDecimal getMinimo() { return minimo; }
    public BigDecimal getMaximo() { return maximo; }
    public UnidadRendimiento getUnidad() { return unidad; }
}
