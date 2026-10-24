package com.planta.acopio.dto;

import java.util.UUID;

// Fase G: tipoPersona (NATURAL por defecto), ruc y razonSocial al final. Persona natural: nombre y DNI obligatorios, RUC
// opcional. Empresa: RUC y razon social obligatorios, sin DNI; el nombre es opcional (si falta se usa la razon social).
// Las reglas se validan en ProveedorService (dependen del tipo). Un cliente anterior que solo envia nombre/dni/telefono/
// zona sigue creando una persona natural como antes.
public record ProveedorRequest(
    String nombre,
    String dni,
    String telefono,
    UUID zonaActualId,
    String tipoPersona,
    String ruc,
    String razonSocial
) {
    public ProveedorRequest(String nombre, String dni, String telefono, UUID zonaActualId) {
        this(nombre, dni, telefono, zonaActualId, null, null, null);
    }
}
