package com.planta.acopio.dto;

import java.util.List;
import java.util.UUID;

// Fase G: documentos de proveedores que una persona debe revisar. Solo lectura: no corrige nada.
public record RevisionDocumentosResponse(
    long totalProveedores,
    long proveedoresConObservaciones,
    List<Observacion> observaciones
) {

    // codigo: DNI_FORMATO (no tiene 8 digitos), DNI_PARECE_RUC (11 digitos con forma de RUC en el campo DNI),
    // DNI_DUPLICADO_PROBABLE (igual a otro DNI salvo espacios o ceros iniciales), RUC_INVALIDO (digito verificador o
    // prefijo incorrecto), RUC_NO_COINCIDE_DNI (RUC 10 que no contiene el DNI de la ficha) o POSIBLE_MISMA_PERSONA
    // (el RUC 10 de una ficha contiene el DNI de otra).
    public record Observacion(
        UUID proveedorId,
        String nombre,
        String tipoPersona,
        String dni,
        String ruc,
        Boolean activo,
        String codigo,
        String detalle
    ) {}
}
