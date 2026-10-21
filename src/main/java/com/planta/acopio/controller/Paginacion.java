package com.planta.acopio.controller;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

// Paginacion pendiente (feat/paginacion-pendiente): mismo criterio que ya
// aplicaban inline los 8 endpoints paginados existentes (ventas, proveedores,
// etc.) -- page negativo se lleva a 0 y size se acota a [1, TAMANO_MAXIMO] en
// vez de responder 400, para que un cliente viejo o un parametro raro nunca
// rompa la pantalla ni pueda pedir la tabla completa de una sola vez.
//
// El Sort siempre debe terminar en una columna unica (id) para que el orden
// sea estable entre paginas: sin desempate, dos filas con la misma fecha
// pueden aparecer en dos paginas o en ninguna.
public final class Paginacion {

    public static final int TAMANO_MAXIMO = 100;
    public static final int TAMANO_POR_DEFECTO = 20;

    private Paginacion() {
    }

    public static Pageable de(int page, int size, Sort orden) {
        return PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), TAMANO_MAXIMO), orden);
    }

    public static Pageable de(int page, int size) {
        return de(page, size, Sort.unsorted());
    }
}
