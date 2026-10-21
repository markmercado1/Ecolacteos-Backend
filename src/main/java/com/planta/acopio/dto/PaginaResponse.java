package com.planta.acopio.dto;

import org.springframework.data.domain.Page;

import java.util.List;

// Bloque "Paginacion real" (confirmado): forma unica y consistente para los
// 4 endpoints paginados (ventas, lotes-produccion, recepcion-planta,
// proveedores) -- generico, un solo DTO reutilizado en vez de uno por recurso.
public record PaginaResponse<T>(
    List<T> contenido,
    int paginaActual,
    int tamanoPagina,
    long totalElementos,
    int totalPaginas
) {
    public static <T> PaginaResponse<T> de(Page<T> page) {
        return new PaginaResponse<>(page.getContent(), page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages());
    }
}
