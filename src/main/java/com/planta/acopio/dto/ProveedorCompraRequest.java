package com.planta.acopio.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * Alta o edicion de un proveedor de compra. Solo nombre y RUC son obligatorios (el alta rapida desde el ingreso envia solo
 * eso). `categorias`: codigos (LECHE, OTROS_INSUMOS...); vacio o ausente = "Otros insumos" al crear, y se conservan al editar
 * si es null. `insumosHabituales`: null = no cambia.
 */
public record ProveedorCompraRequest(
    @NotBlank @Size(max = 150) String nombre,
    @NotBlank @Size(max = 20) String ruc,
    @Size(max = 20) String telefono,
    @Size(max = 100) String contacto,
    List<String> categorias,
    List<UUID> insumosHabituales
) {
    public ProveedorCompraRequest(String nombre, String ruc) {
        this(nombre, ruc, null, null, null, null);
    }
}
