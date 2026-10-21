package com.planta.acopio.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.UUID;

// Fase 1 (Producto + Presentacion): generaliza TipoQueso (tabla tipo_queso renombrada a producto, V10) a
// cualquier familia (Queso, Yogurt, Leche, Mantequilla, Dulce de leche...), no solo queso. Como se cuenta/
// almacena/vende cada producto vive en Presentacion (1:N), no aqui. rendimientoEsperadoPct/cicloCapital son
// atributos de PRODUCCION DE QUESO especificamente (ver LoteProduccionService) -- opcionales desde esta
// migracion porque no aplican igual (o no aplican en absoluto) a otras familias; los quesos existentes
// conservan su valor tal cual.
@Entity
@Table(name = "producto")
public class Producto {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false, unique = true, length = 60)
    private String nombre;

    @ManyToOne(optional = false)
    @JoinColumn(name = "familia_id")
    private Familia familia;

    @Column(name = "rendimiento_esperado_pct", precision = 5, scale = 2)
    private BigDecimal rendimientoEsperadoPct;

    @Embedded
    private RangoRendimiento rangoRendimiento;

    public RangoRendimiento getRangoRendimiento() { return rangoRendimiento; }
    public void setRangoRendimiento(RangoRendimiento rangoRendimiento) { this.rangoRendimiento = rangoRendimiento; }

    @Enumerated(EnumType.STRING)
    @Column(name = "ciclo_capital", length = 12)
    private CicloCapital cicloCapital;

    // mejora #6
    @Column(nullable = false)
    private Boolean activo = true;

    // Nombre del archivo de la foto en el almacenamiento del catalogo (V20); null = sin foto.
    @Column(name = "foto_archivo", length = 80)
    private String fotoArchivo;

    // Fase 1 (V21): control de vencimiento por lote. Apagado por defecto: un producto sin control se comporta
    // exactamente como antes. Se activa/desactiva solo por ControlVencimientoService.
    @Column(name = "controla_vencimiento", nullable = false)
    private Boolean controlaVencimiento = false;

    public Producto() {}

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getNombre() { return nombre; }
    public void setNombre(String nombre) { this.nombre = nombre; }
    public Familia getFamilia() { return familia; }
    public void setFamilia(Familia familia) { this.familia = familia; }
    public BigDecimal getRendimientoEsperadoPct() { return rendimientoEsperadoPct; }
    public void setRendimientoEsperadoPct(BigDecimal rendimientoEsperadoPct) { this.rendimientoEsperadoPct = rendimientoEsperadoPct; }
    public CicloCapital getCicloCapital() { return cicloCapital; }
    public void setCicloCapital(CicloCapital cicloCapital) { this.cicloCapital = cicloCapital; }
    public Boolean getActivo() { return activo; }
    public void setActivo(Boolean activo) { this.activo = activo; }
    public String getFotoArchivo() { return fotoArchivo; }
    public void setFotoArchivo(String fotoArchivo) { this.fotoArchivo = fotoArchivo; }
    public Boolean getControlaVencimiento() { return controlaVencimiento; }
    public void setControlaVencimiento(Boolean controlaVencimiento) { this.controlaVencimiento = controlaVencimiento; }
}
