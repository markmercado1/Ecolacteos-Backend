package com.planta.acopio.dto;

import java.util.List;
import java.util.UUID;

public record ProveedorCompraResponse(
    UUID id,
    String nombre,
    String ruc,
    String telefono,
    String contacto,
    Boolean activo,
    List<Categoria> categorias,
    List<InsumoResumen> insumosHabituales
) {
    public record Categoria(UUID id, String codigo, String nombre) {}

    public record InsumoResumen(UUID id, String nombre, String unidadMedida) {}
}
