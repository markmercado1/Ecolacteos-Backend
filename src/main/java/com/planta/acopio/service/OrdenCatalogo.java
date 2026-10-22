package com.planta.acopio.service;

import org.springframework.data.domain.Sort;

// Orden explicito y estable de los catalogos que no tienen fecha de creacion (zonas, motivos, familias, productos,
// insumos, unidades): alfabetico sin distinguir mayusculas, con el id solo como desempate. Sin esto, un findAll()/
// findByActivo...() devuelve las filas en el orden fisico de PostgreSQL, que cambia al editar una fila (la fila
// editada o recien creada aparecia "al final"). No cambia ningun dato ni contrato: solo el orden de las listas.
final class OrdenCatalogo {

    static final Sort POR_NOMBRE = por("nombre");
    static final Sort POR_DESCRIPCION = por("descripcion");
    static final Sort POR_PLACA = por("placa");

    private OrdenCatalogo() {
    }

    private static Sort por(String propiedad) {
        return Sort.by(Sort.Order.asc(propiedad).ignoreCase(), Sort.Order.asc("id"));
    }
}
