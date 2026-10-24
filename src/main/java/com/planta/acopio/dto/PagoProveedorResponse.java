package com.planta.acopio.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

// Pagos efectivos. Sin DNI ni telefono del proveedor (RECEPCION tambien lo consulta). `aplicado` = suma de sus aplicaciones;
// `sinAplicar` = lo que aun puede aplicarse (0 si esta anulado). Los saldos de las liquidaciones los calcula el servidor.
public record PagoProveedorResponse(
    UUID id,
    String uuidCliente,
    UUID proveedorId,
    String proveedorNombre,
    LocalDateTime fechaHora,
    String modalidad,
    BigDecimal monto,
    String concepto,
    String banco,
    String nroOperacion,
    String nroRecibo,
    String observacion,
    String usuarioNombre,
    String estado,
    String anuladoMotivo,
    String anuladoPorNombre,
    LocalDateTime anuladoEn,
    BigDecimal aplicado,
    BigDecimal sinAplicar,
    List<AplicacionPagoResponse> aplicaciones
) {}
