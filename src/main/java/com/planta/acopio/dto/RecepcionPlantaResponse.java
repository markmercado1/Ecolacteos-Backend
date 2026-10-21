package com.planta.acopio.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record RecepcionPlantaResponse(
    UUID id,
    LocalDate fecha,
    String turno,
    UUID unidadId,
    BigDecimal litrosCampo,
    BigDecimal litrosPlanta,
    BigDecimal diferenciaPct,
    String estado,
    // Segunda auditoria (flujos end-to-end §5/§14-D): dato de referencia, NO
    // autoritativo -- suma de RegistroAcopio.litros de esta unidad en la
    // misma fecha calendario. litrosCampo lo sigue digitando RECEPCION a
    // mano (no se sobreescribe ni se usa para decidir 'estado'); esto solo
    // le da al frontend el numero para contrastar visualmente, cerrando el
    // hallazgo de RegistroAcopioRepository.sumLitrosPorUnidadYFecha, que
    // existia sin ningun consumidor.
    BigDecimal litrosRegistradosAcopio,
    // Fase 3, decision de negocio 3 (opcion B, confirmada): trazabilidad
    // exacta -- ids de los RegistroAcopio vinculados explicitamente a esta
    // recepcion (distinto de litrosRegistradosAcopio, que es un SUM de
    // referencia por unidad+fecha, no un vinculo real).
    List<UUID> registroAcopioIds,
    // Fase 2 de jornada (docs/ESPEC_JORNADA_TRAZABILIDAD.md): campos aditivos, todos null para el historico
    // sin jornada. jornadaId identifica el vinculo unico Jornada<->Recepcion. horaLlegada es la marca
    // temporal real de llegada a planta (distinta de Jornada.horaFin, fin de recoleccion). diferenciaLitros
    // es litrosPlanta - litrosCampo, calculado siempre (nunca almacenado, evita duplicar informacion) --
    // cuando la recepcion viene de una jornada, litrosCampo ES el total esperado autoritativo.
    UUID jornadaId,
    LocalDateTime horaLlegada,
    BigDecimal diferenciaLitros,
    String observacion
) {}
