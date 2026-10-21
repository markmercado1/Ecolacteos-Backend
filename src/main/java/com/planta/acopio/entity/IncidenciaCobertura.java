package com.planta.acopio.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;

// Fase 4 (V23): un ajuste por perdida fisica real dejo reservas de esta presentacion sin cobertura. Append-only.
@Entity
@Table(name = "incidencia_cobertura")
public class IncidenciaCobertura {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "presentacion_id")
    private Presentacion presentacion;

    @ManyToOne
    @JoinColumn(name = "ajuste_inventario_id")
    private AjusteInventario ajuste;

    @Column(name = "unidades_faltantes", nullable = false)
    private Integer unidadesFaltantes;

    @Column(name = "creado_en", nullable = false, updatable = false)
    private LocalDateTime creadoEn;

    public IncidenciaCobertura() {}

    public IncidenciaCobertura(Presentacion presentacion, AjusteInventario ajuste, int unidadesFaltantes, LocalDateTime creadoEn) {
        this.presentacion = presentacion;
        this.ajuste = ajuste;
        this.unidadesFaltantes = unidadesFaltantes;
        this.creadoEn = creadoEn;
    }

    public UUID getId() { return id; }
    public Presentacion getPresentacion() { return presentacion; }
    public AjusteInventario getAjuste() { return ajuste; }
    public Integer getUnidadesFaltantes() { return unidadesFaltantes; }
    public LocalDateTime getCreadoEn() { return creadoEn; }
}
