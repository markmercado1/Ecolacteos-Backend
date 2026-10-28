package com.planta.acopio.service;

import com.planta.acopio.entity.ReglaComprador;
import com.planta.acopio.entity.TipoPrecio;
import com.planta.acopio.exception.ValidacionNegocioException;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.lenient;

/** Catalogo de tipos de precio simulado para pruebas unitarias: los tres originales activos y cualquier otro codigo desconocido se rechaza. */
final class TiposPrecioSimulados {

    private TiposPrecioSimulados() {}

    static TipoPrecioService activos() {
        TipoPrecioService s = mock(TipoPrecioService.class);
        lenient().when(s.obtener(anyString())).thenAnswer(inv -> tipo(inv.getArgument(0)));
        lenient().when(s.exigirActivo(anyString())).thenAnswer(inv -> tipo(inv.getArgument(0)));
        return s;
    }

    private static TipoPrecio tipo(String codigo) {
        ReglaComprador regla = switch (codigo) {
            case "PUBLICO" -> ReglaComprador.CUALQUIERA;
            case "MAYORISTA" -> ReglaComprador.CLIENTE_HABILITADO;
            case "PROVEEDOR" -> ReglaComprador.PROVEEDOR_PADRON;
            default -> throw new ValidacionNegocioException("Tipo de precio no reconocido: " + codigo);
        };
        TipoPrecio t = new TipoPrecio();
        t.setCodigo(codigo);
        t.setNombre(codigo);
        t.setReglaComprador(regla);
        return t;
    }
}
