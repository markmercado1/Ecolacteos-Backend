package com.planta.acopio.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.UUID;

// Trazabilidad del nuevo modelo funcional de Produccion (refactor fase 2, V9): de que RecepcionPlanta vino
// la leche de un lote, y cuantos litros de cada una -- asignado por FIFO automatico contra las recepciones
// con saldo remanente (ver LoteProduccionService.planificarConsumo()), el usuario ya no selecciona registros
// de acopio uno por uno. Entidad de asociacion con atributo propio (litrosConsumidos), mismo criterio que
// InformeProduccionInsumo: id propio generado, no PK compuesta. Deliberadamente sin restriccion que impida
// reutilizar una recepcion en varios lotes ni que un lote consuma de varias recepciones (consumo parcial
// real, decision aprobada del refactor) -- el unico UNIQUE de la migracion es (lote, recepcion).
@Entity
@Table(name = "lote_produccion_recepcion_consumo")
public class LoteProduccionRecepcionConsumo {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "lote_id")
    private LoteProduccion loteProduccion;

    @ManyToOne(optional = false)
    @JoinColumn(name = "recepcion_planta_id")
    private RecepcionPlanta recepcionPlanta;

    @Column(name = "litros_consumidos", nullable = false, precision = 9, scale = 2)
    private BigDecimal litrosConsumidos;

    public LoteProduccionRecepcionConsumo() {}

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public LoteProduccion getLoteProduccion() { return loteProduccion; }
    public void setLoteProduccion(LoteProduccion loteProduccion) { this.loteProduccion = loteProduccion; }
    public RecepcionPlanta getRecepcionPlanta() { return recepcionPlanta; }
    public void setRecepcionPlanta(RecepcionPlanta recepcionPlanta) { this.recepcionPlanta = recepcionPlanta; }
    public BigDecimal getLitrosConsumidos() { return litrosConsumidos; }
    public void setLitrosConsumidos(BigDecimal litrosConsumidos) { this.litrosConsumidos = litrosConsumidos; }
}
