package com.planta.acopio.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

// Refactor de Produccion, fase 2 (nuevo modelo funcional): ya NO se acompaña de registroAcopioIds -- el
// backend decide automaticamente de que RecepcionPlanta viene la leche (FIFO por antiguedad sobre el saldo
// remanente, ver LoteProduccionService.planificarConsumo()); el cliente solo indica cuanta leche usar.
// litrosUsados es estrictamente > 0.
//
// Fase 3 (Produccion con varias presentaciones de salida): un lote corresponde a UN producto elaborado pero
// puede generar VARIAS presentaciones de salida -- `detalles` reemplaza al antiguo `unidadesObtenidas`
// unico. `detalles` puede ser una lista VACIA: representa el flujo real, ya existente antes de esta fase,
// de una produccion que consumio leche pero no obtuvo ningun rendimiento aprovechable (lote fallido/perdido)
// -- se preserva como caso explicito, nunca como una linea de detalle en cero (ver
// LoteProduccionService.crear()). Cuando no esta vacia, exige al menos una salida positiva por construccion
// (cada DetalleSalidaRequest.cantidadObtenida ya es >= 1); no-duplicados y pertenencia al producto se
// validan en el service.
public record CrearLoteRequest(
    @NotBlank String uuidCliente,           // idempotencia: reenviar el mismo valor nunca duplica el lote
    @NotNull LocalDate fecha,
    @NotNull UUID productoId,
    @NotNull @DecimalMin(value = "0.0", inclusive = false) @Digits(integer = 7, fraction = 2) BigDecimal litrosUsados,
    @NotNull @Valid List<@NotNull DetalleSalidaRequest> detalles,
    @Size(max = 500) String motivoRendimiento,
    // Insumos usados (cuajo, sal, ...). OPCIONAL: null o vacio = lote sin insumos del catalogo (no se uso
    // ninguno, o el insumo usado no existe en el catalogo) y se registra igual. null tambien mantiene
    // compatible a la app movil, que todavia no envia este campo.
    @Valid List<@NotNull InsumoUsadoRequest> insumos
) {
    public CrearLoteRequest(String uuidCliente, LocalDate fecha, UUID productoId, BigDecimal litrosUsados,
                            List<DetalleSalidaRequest> detalles) {
        this(uuidCliente, fecha, productoId, litrosUsados, detalles, null, null);
    }

    public CrearLoteRequest(String uuidCliente, LocalDate fecha, UUID productoId, BigDecimal litrosUsados,
                            List<DetalleSalidaRequest> detalles, String motivoRendimiento) {
        this(uuidCliente, fecha, productoId, litrosUsados, detalles, motivoRendimiento, null);
    }

    public List<InsumoUsadoRequest> insumosOVacio() {
        return insumos == null ? List.of() : insumos;
    }
}
