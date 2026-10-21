package com.planta.acopio.service;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

// Fase 2: desglose de las existencias de UNA presentacion controlada, calculado desde el libro y el reparto por
// lote (nada se almacena). Invariante:  fisico = sinTrazabilidad + SUM(lotes.saldo)  y
// fisico = vendible + vencido + sinTrazabilidad.
public record Existencias(int fisico, int sinTrazabilidad, int vencido, int vendible, List<Lote> lotes) {

    public enum Origen { LOTE, REGULARIZACION }

    /** Un lote con saldo. `lotes` viene en orden FEFO (vence antes primero). `fechaLote` es la fecha de produccion
     *  (para una regularizacion, la fecha en que se regularizo). */
    public record Lote(Origen origen, UUID id, UUID loteProduccionId, LocalDate fechaLote, LocalDate fechaVencimiento,
                       int saldo, boolean vencido) {}
}
