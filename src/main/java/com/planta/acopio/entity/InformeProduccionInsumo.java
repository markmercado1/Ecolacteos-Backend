package com.planta.acopio.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.UUID;

// Entidad de asociacion InformeProduccionDiario <-> Insumo con atributo propio (cantidad) -- no es un
// simple N:M (como LoteProduccion.registrosVinculados), necesita su propia fila. UNIQUE(informe_id,
// insumo_id) en la migracion evita dos filas del mismo insumo para el mismo informe.
@Entity
@Table(name = "informe_produccion_insumo")
public class InformeProduccionInsumo {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "informe_id")
    private InformeProduccionDiario informe;

    @ManyToOne(optional = false)
    @JoinColumn(name = "insumo_id")
    private Insumo insumo;

    @Column(nullable = false, precision = 9, scale = 2)
    private BigDecimal cantidad;

    public InformeProduccionInsumo() {}

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public InformeProduccionDiario getInforme() { return informe; }
    public void setInforme(InformeProduccionDiario informe) { this.informe = informe; }
    public Insumo getInsumo() { return insumo; }
    public void setInsumo(Insumo insumo) { this.insumo = insumo; }
    public BigDecimal getCantidad() { return cantidad; }
    public void setCantidad(BigDecimal cantidad) { this.cantidad = cantidad; }
}
