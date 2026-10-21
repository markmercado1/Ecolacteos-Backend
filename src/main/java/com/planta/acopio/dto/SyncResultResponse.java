package com.planta.acopio.dto;

import java.util.List;
import java.util.UUID;

// DATA-014 (jornada de reconciliacion real): antes, un cliente no tenia forma
// de relacionar el uuidCliente que ya conocia con el id real que Postgres le
// asigno al confirmar por lote -- 'confirmados' solo traia el uuidCliente de
// vuelta. 'confirmadosConId' es aditivo a proposito: 'confirmados' y 'errores'
// no cambian de forma, asi que un cliente movil que todavia no actualizo sigue
// funcionando exactamente igual (ignora la clave nueva). Un cliente actualizado
// puede ahora persistir el id real y dejar de depender de 'server_id = null'.
public record SyncResultResponse(
    List<String> confirmados,
    List<SyncErrorItem> errores,
    List<ConfirmacionItem> confirmadosConId
) {
    public record SyncErrorItem(String uuidCliente, String motivo) {}

    // Un item por cada uuidCliente en 'confirmados' (mismo tamano y mismos
    // uuidCliente que esa lista, nunca los de 'errores'): el id real que el
    // service le devolvio al crear O AL ENCONTRAR el existente (idempotencia
    // por uuidCliente, ver cada Service) -- un reintento del mismo uuidCliente
    // siempre devuelve el mismo id, nunca uno nuevo.
    public record ConfirmacionItem(String uuidCliente, UUID id) {}
}
