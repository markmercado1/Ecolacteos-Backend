package com.planta.acopio.excel;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/** De donde salen las filas de una exportacion: la MISMA consulta paginada que alimenta la tabla en pantalla. */
@FunctionalInterface
public interface FuentePaginada<T> {
    Page<T> pagina(Pageable pageable);
}
