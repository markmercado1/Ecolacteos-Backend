package com.planta.acopio.excel;

import com.planta.acopio.dto.PaginaResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.util.function.Function;

/** De donde salen las filas de una exportacion: la MISMA consulta paginada que alimenta la tabla en pantalla. */
@FunctionalInterface
public interface FuentePaginada<T> {
    Page<T> pagina(Pageable pageable);

    /** Adapta los servicios que ya devuelven {@link PaginaResponse} (reservas, stock, precios por presentacion). */
    static <T> FuentePaginada<T> deRespuesta(Function<Pageable, PaginaResponse<T>> origen) {
        return pageable -> {
            PaginaResponse<T> respuesta = origen.apply(pageable);
            return new PageImpl<>(respuesta.contenido(), pageable, respuesta.totalElementos());
        };
    }
}
