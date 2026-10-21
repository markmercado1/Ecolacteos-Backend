package com.planta.acopio.service;

import com.planta.acopio.dto.DisponibilidadProduccionResponse.Linea;
import com.planta.acopio.entity.Insumo;
import com.planta.acopio.entity.Presentacion;
import com.planta.acopio.entity.Producto;
import com.planta.acopio.entity.Receta;
import com.planta.acopio.entity.RecetaInsumo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// Receta de ejemplo del requerimiento: Queso Andino, base 100 L leche + 1 g cuajo + 1 kg sal -> 1 pieza.
class CalculoRecetaTest {

    private Receta receta;
    private Insumo cuajo;
    private Insumo sal;

    @BeforeEach
    void setUp() {
        Producto andino = new Producto();
        andino.setId(UUID.randomUUID());
        andino.setNombre("Queso Andino");
        Presentacion pieza = new Presentacion();
        pieza.setId(UUID.randomUUID());
        pieza.setNombre("Pieza");
        pieza.setUnidadConteo("pieza");
        pieza.setProducto(andino);

        receta = new Receta();
        receta.setNombre("Receta estándar");
        receta.setProducto(andino);
        receta.setPresentacion(pieza);
        receta.setLitrosBase(new BigDecimal("100"));
        receta.setRendimientoBase(BigDecimal.ONE);
        cuajo = insumo("Cuajo", "g", "15");
        sal = insumo("Sal", "kg", "20");
        receta.getInsumos().add(fila(cuajo, "1"));
        receta.getInsumos().add(fila(sal, "1"));
    }

    private Insumo insumo(String nombre, String unidad, String stock) {
        Insumo i = new Insumo();
        i.setId(UUID.randomUUID());
        i.setNombre(nombre);
        i.setUnidadMedida(unidad);
        i.setStock(new BigDecimal(stock));
        return i;
    }

    private RecetaInsumo fila(Insumo insumo, String cantidad) {
        RecetaInsumo ri = new RecetaInsumo();
        ri.setReceta(receta);
        ri.setInsumo(insumo);
        ri.setCantidad(new BigDecimal(cantidad));
        return ri;
    }

    @ParameterizedTest
    @CsvSource({"100, 1, 1", "1000, 10, 10", "500, 5, 5", "650, 6.5, 6.5"})
    void escalaProporcionalmenteLaSalidaEsperadaYLosInsumos(String litros, String esperada, String porInsumo) {
        BigDecimal l = new BigDecimal(litros);

        assertThat(CalculoReceta.cantidadEsperada(receta, l)).isEqualByComparingTo(esperada);
        List<CalculoReceta.Requerimiento> req = CalculoReceta.requerimientos(receta, l);
        assertThat(req).extracting(r -> r.insumo().getNombre()).containsExactly("Cuajo", "Sal");
        assertThat(req).allSatisfy(r -> assertThat(r.requerido()).isEqualByComparingTo(porInsumo));
    }

    @Test
    void elFactorEsLitrosSobreLaBase() {
        assertThat(CalculoReceta.factor(new BigDecimal("1000"), new BigDecimal("100"))).isEqualByComparingTo("10");
        assertThat(CalculoReceta.factor(new BigDecimal("50"), new BigDecimal("100"))).isEqualByComparingTo("0.5");
    }

    @Test
    void escalaSinPerderPrecisionConUnFactorPeriodico() {
        // 1 g por 300 L -> 100 L = 0.333... g: se multiplica antes de dividir, redondeo a 2 decimales.
        assertThat(CalculoReceta.escalar(BigDecimal.ONE, new BigDecimal("100"), new BigDecimal("300"), 2))
                .isEqualByComparingTo("0.33");
        assertThat(CalculoReceta.escalar(BigDecimal.ONE, new BigDecimal("900"), new BigDecimal("300"), 2))
                .isEqualByComparingTo("3");
    }

    // ---- Validación de stock (ejemplos del requerimiento) ----

    private List<Linea> lineas(String litros) {
        return CalculoReceta.lineasInsumos(CalculoReceta.requerimientos(receta, new BigDecimal(litros)));
    }

    @Test
    void todosLosInsumosDisponibles() {
        Linea leche = CalculoReceta.lineaLeche(new BigDecimal("1000"), new BigDecimal("1500"));
        List<Linea> insumos = lineas("1000");

        assertThat(leche.suficiente()).isTrue();
        assertThat(insumos).allMatch(Linea::suficiente);
        assertThat(CalculoReceta.todoDisponible(leche, insumos)).isTrue();
    }

    @Test
    void faltaSal() {
        sal.setStock(new BigDecimal("6"));
        Linea leche = CalculoReceta.lineaLeche(new BigDecimal("1000"), new BigDecimal("1500"));
        List<Linea> insumos = lineas("1000");

        Linea lineaSal = insumos.get(1);
        assertThat(lineaSal.suficiente()).isFalse();
        assertThat(lineaSal.requerido()).isEqualByComparingTo("10");
        assertThat(lineaSal.disponible()).isEqualByComparingTo("6");
        assertThat(lineaSal.faltante()).isEqualByComparingTo("4");
        assertThat(insumos.get(0).suficiente()).isTrue();
        assertThat(CalculoReceta.todoDisponible(leche, insumos)).isFalse();
        assertThat(CalculoReceta.mensajeFaltantes(leche, insumos))
                .contains("faltan 4 kg de Sal")
                .doesNotContain("Leche:")
                .contains("Repón el stock");
    }

    @Test
    void faltaCuajo() {
        cuajo.setStock(new BigDecimal("8"));
        List<Linea> insumos = lineas("1000");

        assertThat(insumos.get(0).suficiente()).isFalse();
        assertThat(insumos.get(0).faltante()).isEqualByComparingTo("2");
        assertThat(insumos.get(1).suficiente()).isTrue();
    }

    @Test
    void faltaLecheConSolicitadoDisponibleYFaltante() {
        Linea leche = CalculoReceta.lineaLeche(new BigDecimal("1000"), new BigDecimal("650"));

        assertThat(leche.suficiente()).isFalse();
        assertThat(leche.faltante()).isEqualByComparingTo("350");
        assertThat(CalculoReceta.mensajeFaltantes(leche, lineas("1000")))
                .contains("Leche: solicitado 1000 L, disponible 650 L, faltan 350 L");
    }

    @Test
    void faltanVariosInsumosYSeInformanTodos() {
        cuajo.setStock(new BigDecimal("5"));
        sal.setStock(new BigDecimal("6"));
        Linea leche = CalculoReceta.lineaLeche(new BigDecimal("1000"), new BigDecimal("650"));

        String mensaje = CalculoReceta.mensajeFaltantes(leche, lineas("1000"));

        assertThat(mensaje).contains("faltan 350 L").contains("faltan 5 g de Cuajo").contains("faltan 4 kg de Sal");
    }

    @Test
    void stockExactamenteIgualAlNecesarioAlcanza() {
        cuajo.setStock(new BigDecimal("10.00"));
        sal.setStock(new BigDecimal("10"));
        Linea leche = CalculoReceta.lineaLeche(new BigDecimal("1000"), new BigDecimal("1000.00"));

        assertThat(CalculoReceta.todoDisponible(leche, lineas("1000"))).isTrue();
    }

    @Test
    void stockCeroONuloEsInsuficiente() {
        sal.setStock(null);
        Linea lineaSal = lineas("100").get(1);

        assertThat(lineaSal.disponible()).isEqualByComparingTo("0");
        assertThat(lineaSal.suficiente()).isFalse();
    }

    // ---- Máximo producible (informativo, nunca aplicado automáticamente) ----

    @Test
    void maximoProducibleLimitadoPorLaLeche() {
        BigDecimal maximo = CalculoReceta.litrosMaximos(receta, new BigDecimal("650"));

        assertThat(maximo).isEqualByComparingTo("650");
        assertThat(CalculoReceta.cantidadEsperadaEntera(receta, maximo)).isEqualByComparingTo("6");
    }

    @Test
    void maximoProducibleLimitadoPorElInsumoMasEscaso() {
        sal.setStock(new BigDecimal("6")); // 6 kg de sal alcanzan para 600 L

        BigDecimal maximo = CalculoReceta.litrosMaximos(receta, new BigDecimal("1500"));

        assertThat(maximo).isEqualByComparingTo("600");
        assertThat(CalculoReceta.cantidadEsperadaEntera(receta, maximo)).isEqualByComparingTo("6");
    }

    @Test
    void maximoProducibleNuncaSugiereAlgoQueNoAlcanzaPorRedondeo() {
        // 1 g por 300 L con 1 g de stock: 300 L exactos alcanzan; el redondeo de 2 decimales no debe
        // proponer un valor cuyo requerido redondeado supere el stock.
        receta.getInsumos().clear();
        Insumo cultivo = insumo("Cultivo", "g", "0.01");
        receta.setLitrosBase(new BigDecimal("300"));
        receta.getInsumos().add(fila(cultivo, "0.01"));

        BigDecimal maximo = CalculoReceta.litrosMaximos(receta, new BigDecimal("1000"));

        List<Linea> insumos = CalculoReceta.lineasInsumos(CalculoReceta.requerimientos(receta, maximo));
        assertThat(insumos).allMatch(Linea::suficiente);
        assertThat(maximo).isLessThanOrEqualTo(new BigDecimal("450"));
    }

    @Test
    void sinLecheElMaximoEsCero() {
        assertThat(CalculoReceta.litrosMaximos(receta, BigDecimal.ZERO)).isEqualByComparingTo("0");
    }
}
