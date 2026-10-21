package com.planta.acopio.service;

import com.planta.acopio.dto.DetalleSalidaRequest;
import com.planta.acopio.entity.Presentacion;
import com.planta.acopio.entity.RangoRendimiento;
import com.planta.acopio.entity.UnidadContenido;
import com.planta.acopio.entity.UnidadRendimiento;
import com.planta.acopio.exception.ValidacionNegocioException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Comparación física explícita; nunca convierte litros en kilos ni suma piezas de distintos tamaños. */
public record RevisionRendimiento(boolean fueraDeRango, String descripcion) {
    public static RevisionRendimiento evaluar(RangoRendimiento rango, BigDecimal litros,
            List<DetalleSalidaRequest> detalles, List<Presentacion> presentaciones) {
        if (rango == null) return new RevisionRendimiento(false, null);
        if (rango.getUnidad() == UnidadRendimiento.UNIDAD && detalles.size() > 1) {
            throw new ValidacionNegocioException(
                    "Un rango en unidades requiere una sola presentación. Configura un rango en kg o L para combinar tamaños.");
        }
        Map<UUID, Presentacion> porId = presentaciones.stream()
                .collect(Collectors.toMap(Presentacion::getId, Function.identity()));
        BigDecimal salida = BigDecimal.ZERO;
        for (DetalleSalidaRequest detalle : detalles) {
            salida = salida.add(contenidoEnUnidad(porId.get(detalle.presentacionId()), rango.getUnidad())
                    .multiply(BigDecimal.valueOf(detalle.cantidadObtenida())));
        }
        BigDecimal salidaPorCien = salida.multiply(BigDecimal.valueOf(100));
        boolean fuera = salidaPorCien.compareTo(rango.getMinimo().multiply(litros)) < 0
                || salidaPorCien.compareTo(rango.getMaximo().multiply(litros)) > 0;
        String descripcion = salidaPorCien.divide(litros, 4, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
                + " " + rango.getUnidad() + " por 100 L; rango configurado: "
                + rango.getMinimo().stripTrailingZeros().toPlainString() + "–"
                + rango.getMaximo().stripTrailingZeros().toPlainString() + " " + rango.getUnidad();
        return new RevisionRendimiento(fuera, descripcion);
    }

    private static BigDecimal contenidoEnUnidad(Presentacion presentacion, UnidadRendimiento unidad) {
        if (unidad == UnidadRendimiento.UNIDAD) return BigDecimal.ONE;
        BigDecimal contenido = presentacion.getContenidoCantidad();
        UnidadContenido medida = presentacion.getContenidoUnidad();
        if (contenido != null && contenido.signum() > 0 && medida != null) {
            if ((unidad == UnidadRendimiento.KG && medida == UnidadContenido.KG)
                    || (unidad == UnidadRendimiento.L && medida == UnidadContenido.L)) return contenido;
            if ((unidad == UnidadRendimiento.KG && medida == UnidadContenido.G)
                    || (unidad == UnidadRendimiento.L && medida == UnidadContenido.ML)) return contenido.movePointLeft(3);
        }
        throw new ValidacionNegocioException("La presentación '" + presentacion.getNombre()
                + "' necesita un contenido compatible con el rango en " + unidad + ". Revisa el catálogo.");
    }
}
