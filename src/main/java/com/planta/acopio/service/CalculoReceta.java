package com.planta.acopio.service;

import com.planta.acopio.dto.DisponibilidadProduccionResponse;
import com.planta.acopio.dto.DisponibilidadProduccionResponse.Linea;
import com.planta.acopio.entity.Insumo;
import com.planta.acopio.entity.Receta;
import com.planta.acopio.entity.RecetaInsumo;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Calculo proporcional de recetas (V18), unico lugar donde se escala una receta: el frontend solo muestra
 * lo que devuelve el backend. Todas las cantidades de una receta son "por litrosBase" de leche:
 * requerido = cantidadBase x litros / litrosBase (se multiplica antes de dividir para no perder precision
 * en un factor periodico). Sin conversion de unidades: cada insumo se escala en su propia unidad de medida.
 */
public final class CalculoReceta {

    /** Escala de insumo.stock y lote_produccion_insumo.cantidad (NUMERIC(..,2)). */
    public static final int ESCALA_INSUMO = 2;
    /** Escala de lote_produccion.cantidad_esperada (NUMERIC(12,3)). */
    public static final int ESCALA_ESPERADA = 3;
    private static final int ESCALA_FACTOR = 4;
    private static final int ESCALA_LITROS = 2;

    private CalculoReceta() {}

    public record Requerimiento(Insumo insumo, BigDecimal requerido) {}

    public static BigDecimal factor(BigDecimal litros, BigDecimal litrosBase) {
        return litros.divide(litrosBase, ESCALA_FACTOR, RoundingMode.HALF_UP);
    }

    public static BigDecimal escalar(BigDecimal cantidadBase, BigDecimal litros, BigDecimal litrosBase, int escala) {
        return cantidadBase.multiply(litros).divide(litrosBase, escala, RoundingMode.HALF_UP);
    }

    public static BigDecimal cantidadEsperada(Receta receta, BigDecimal litros) {
        return escalar(receta.getRendimientoBase(), litros, receta.getLitrosBase(), ESCALA_ESPERADA);
    }

    public static List<Requerimiento> requerimientos(Receta receta, BigDecimal litros) {
        return receta.getInsumos().stream()
                .map(ri -> new Requerimiento(ri.getInsumo(),
                        escalar(ri.getCantidad(), litros, receta.getLitrosBase(), ESCALA_INSUMO)))
                .toList();
    }

    public static Linea linea(UUID insumoId, String nombre, String unidadMedida, BigDecimal requerido, BigDecimal disponible) {
        BigDecimal disponibleNoNulo = disponible == null ? BigDecimal.ZERO : disponible;
        BigDecimal faltante = requerido.subtract(disponibleNoNulo).max(BigDecimal.ZERO);
        // Stock exactamente igual al requerido alcanza (compareTo, no equals: "10" y "10.00" son lo mismo).
        return new Linea(insumoId, nombre, unidadMedida, requerido, disponibleNoNulo, faltante, faltante.signum() == 0);
    }

    public static Linea lineaLeche(BigDecimal litros, BigDecimal lecheDisponible) {
        return linea(null, "Leche", "L", litros, lecheDisponible);
    }

    public static List<Linea> lineasInsumos(List<Requerimiento> requerimientos) {
        return requerimientos.stream()
                .map(r -> linea(r.insumo().getId(), r.insumo().getNombre(), r.insumo().getUnidadMedida(),
                        r.requerido(), r.insumo().getStock()))
                .toList();
    }

    public static boolean todoDisponible(Linea leche, List<Linea> insumos) {
        return leche.suficiente() && insumos.stream().allMatch(Linea::suficiente);
    }

    /**
     * Litros maximos producibles con el stock actual: el minimo entre la leche disponible y, por cada
     * insumo, stock x litrosBase / cantidadBase. Redondeado HACIA ABAJO (nunca sugiere algo que no alcanza).
     */
    public static BigDecimal litrosMaximos(Receta receta, BigDecimal lecheDisponible) {
        BigDecimal maximo = lecheDisponible == null ? BigDecimal.ZERO : lecheDisponible.max(BigDecimal.ZERO);
        for (RecetaInsumo ri : receta.getInsumos()) {
            BigDecimal stock = ri.getInsumo().getStock() == null ? BigDecimal.ZERO : ri.getInsumo().getStock();
            BigDecimal porInsumo = stock.multiply(receta.getLitrosBase())
                    .divide(ri.getCantidad(), ESCALA_LITROS, RoundingMode.DOWN);
            maximo = maximo.min(porInsumo);
        }
        maximo = maximo.setScale(ESCALA_LITROS, RoundingMode.DOWN);
        // El redondeo HALF_UP de cada insumo requerido podria superar por un centesimo el stock en el limite:
        // se retrocede de a 0.01 L hasta que todo lo requerido alcance (normalmente 0-1 pasos; acotado).
        BigDecimal paso = new BigDecimal("0.01");
        for (int i = 0; i < 10_000 && maximo.signum() > 0 && !alcanza(receta, maximo, lecheDisponible); i++) {
            maximo = maximo.subtract(paso);
        }
        return maximo.max(BigDecimal.ZERO);
    }

    private static boolean alcanza(Receta receta, BigDecimal litros, BigDecimal lecheDisponible) {
        if (lecheDisponible == null || litros.compareTo(lecheDisponible) > 0) return false;
        return requerimientos(receta, litros).stream().allMatch(r -> {
            BigDecimal stock = r.insumo().getStock() == null ? BigDecimal.ZERO : r.insumo().getStock();
            return r.requerido().compareTo(stock) <= 0;
        });
    }

    /** Salida entera esperada con esos litros (no se puede producir media pieza): redondeo hacia abajo. */
    public static BigDecimal cantidadEsperadaEntera(Receta receta, BigDecimal litros) {
        return receta.getRendimientoBase().multiply(litros)
                .divide(receta.getLitrosBase(), 0, RoundingMode.DOWN);
    }

    /** Mensaje legible con TODOS los faltantes (no solo el primero), para el rechazo de iniciar/registrar. */
    public static String mensajeFaltantes(Linea leche, List<Linea> insumos) {
        StringBuilder sb = new StringBuilder("Stock insuficiente para esta producción. ");
        if (!leche.suficiente()) {
            sb.append("Leche: solicitado ").append(plano(leche.requerido())).append(" L, disponible ")
                    .append(plano(leche.disponible())).append(" L, faltan ").append(plano(leche.faltante()))
                    .append(" L. ");
        }
        String faltantes = insumos.stream().filter(l -> !l.suficiente())
                .map(l -> "faltan " + plano(l.faltante()) + " " + l.unidadMedida() + " de " + l.nombre()
                        + " (necesario " + plano(l.requerido()) + ", disponible " + plano(l.disponible()) + ")")
                .collect(Collectors.joining("; "));
        if (!faltantes.isEmpty()) sb.append("Insumos: ").append(faltantes).append(". ");
        sb.append("Repón el stock antes de continuar.");
        return sb.toString();
    }

    public static DisponibilidadProduccionResponse respuesta(BigDecimal litros, BigDecimal factor, Linea leche,
            List<Linea> insumos, BigDecimal cantidadEsperada, String presentacionNombre, String unidadConteo,
            BigDecimal litrosMaximos, BigDecimal cantidadEsperadaMaxima) {
        return new DisponibilidadProduccionResponse(litros, factor, leche, insumos, cantidadEsperada,
                presentacionNombre, unidadConteo, todoDisponible(leche, insumos), litrosMaximos, cantidadEsperadaMaxima);
    }

    private static String plano(BigDecimal valor) {
        return valor.stripTrailingZeros().toPlainString();
    }
}
