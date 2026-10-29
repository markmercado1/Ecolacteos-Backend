package com.planta.acopio.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Insumos con el mismo nombre normalizado (sin mayusculas, tildes ni espacios sobrantes), agrupados para fusionarlos. */
public record DuplicadosInsumoResponse(
    /** true si la base de datos ya garantiza la unicidad (indice unico creado: no quedan duplicados). */
    boolean indiceUnicoActivo,
    List<Grupo> grupos
) {
    public record Grupo(String clave, List<Insumo> insumos) {}

    public record Insumo(UUID id, String nombre, String unidadMedida, boolean activo, BigDecimal stock, long movimientos) {}
}
