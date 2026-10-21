package com.planta.acopio.service.util;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

// Estadistica simple (media, desviacion estandar, coeficiente de variacion),
// deliberadamente sin librerias de ML -- CORE_DESIGN.md / documento fuente §2.10:
// "todo se calcula en el servidor con estadistica simple... nada de librerias
// de Machine Learning pesadas, porque el objetivo es que sea implementable en
// tiempo de curso y explicable ante un jurado."
public final class EstadisticaSimple {

    private EstadisticaSimple() {}

    public static double media(List<BigDecimal> valores) {
        if (valores == null || valores.isEmpty()) return 0.0;
        return valores.stream().mapToDouble(BigDecimal::doubleValue).average().orElse(0.0);
    }

    public static double desviacionEstandar(List<BigDecimal> valores, double media) {
        if (valores == null || valores.size() < 2) return 0.0;
        double sumaCuadrados = valores.stream()
                .mapToDouble(v -> Math.pow(v.doubleValue() - media, 2))
                .sum();
        return Math.sqrt(sumaCuadrados / (valores.size() - 1));
    }

    // std / media, acotado a util para "componente_regularidad" (RF-INN-01):
    // mide que tan erratico entrega el proveedor, no si entrega poco o mucho.
    public static double coeficienteVariacion(List<BigDecimal> valores) {
        double m = media(valores);
        if (m == 0.0) return 0.0;
        return desviacionEstandar(valores, m) / m;
    }

    public static BigDecimal redondear(double valor, int escala) {
        return BigDecimal.valueOf(valor).setScale(escala, RoundingMode.HALF_UP);
    }
}
