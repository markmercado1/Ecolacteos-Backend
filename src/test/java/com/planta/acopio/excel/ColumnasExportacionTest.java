package com.planta.acopio.excel;

import com.planta.acopio.dto.FilaPrecioPresentacionResponse;
import com.planta.acopio.dto.PrecioModalidadResponse;
import com.planta.acopio.dto.PresentacionResponse;
import com.planta.acopio.dto.ProductoResponse;
import com.planta.acopio.dto.RecepcionPlantaResponse;
import com.planta.acopio.dto.RecetaResponse;
import com.planta.acopio.dto.ReservaResumenResponse;
import com.planta.acopio.dto.UnidadResponse;
import com.planta.acopio.dto.UsuarioResponse;
import com.planta.acopio.entity.EstadoReserva;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ColumnasExportacionTest {

    private static <T> List<Object> valores(List<ColumnaExcel<T>> columnas, T fila) {
        List<Object> lista = new ArrayList<>();
        for (ColumnaExcel<T> c : columnas) lista.add(c.valor().apply(fila));
        return lista;
    }

    private static <T> Object valor(List<ColumnaExcel<T>> columnas, String titulo, T fila) {
        return columnas.stream().filter(c -> c.titulo().equals(titulo)).findFirst().orElseThrow().valor().apply(fila);
    }

    @Test
    @SuppressWarnings("unchecked")
    void todasLasListasTienenTitulosUnicosYNoVacios() throws Exception {
        int listas = 0;
        for (Field campo : ColumnasExportacion.class.getDeclaredFields()) {
            if (!Modifier.isStatic(campo.getModifiers()) || !List.class.isAssignableFrom(campo.getType())) continue;
            campo.setAccessible(true);
            List<ColumnaExcel<?>> columnas = (List<ColumnaExcel<?>>) campo.get(null);
            Set<String> vistos = new HashSet<>();
            for (ColumnaExcel<?> c : columnas) {
                assertThat(c.titulo()).as("titulo en %s", campo.getName()).isNotBlank();
                assertThat(vistos.add(c.titulo())).as("titulo repetido '%s' en %s", c.titulo(), campo.getName()).isTrue();
                assertThat(c.ancho()).isBetween(8, 100);
            }
            listas++;
        }
        assertThat(listas).isGreaterThanOrEqualTo(20);
    }

    @Test
    void usuariosNuncaExponenContrasenaNiHash() {
        // UsuarioResponse no las tiene; se verifica que ninguna columna pretenda mostrarlas.
        assertThat(ColumnasExportacion.USUARIOS).extracting(ColumnaExcel::titulo)
                .noneMatch(t -> t.toLowerCase().contains("contrase") || t.toLowerCase().contains("hash") || t.toLowerCase().contains("password"));
        var fila = new UsuarioResponse(UUID.randomUUID(), "Ana", "ana@planta.com", "ADMIN", true);
        assertThat(valores(ColumnasExportacion.USUARIOS, fila)).containsExactly("Ana", "ana@planta.com", "ADMIN", true);
    }

    @Test
    void reservaConPrecioPorConfirmarNoInventaUnTotal() {
        var porConfirmar = new ReservaResumenResponse(UUID.randomUUID(), "RES-001", EstadoReserva.PENDIENTE, "Rosa Quispe", "+51987654321",
                LocalDate.of(2026, 10, 25), LocalDateTime.of(2026, 10, 22, 9, 0), LocalDateTime.of(2026, 10, 23, 9, 0),
                3, new BigDecimal("0.00"), true, true, false, false);
        var confirmada = new ReservaResumenResponse(UUID.randomUUID(), "RES-002", EstadoReserva.CONFIRMADA, "Luis", "+51912345678",
                LocalDate.of(2026, 10, 25), LocalDateTime.of(2026, 10, 22, 9, 0), null, 2, new BigDecimal("45.50"), false, false, true, true);

        assertThat(valor(ColumnasExportacion.RESERVAS, "Total", porConfirmar)).isNull();
        assertThat(valor(ColumnasExportacion.RESERVAS, "Total", confirmada)).isEqualTo(new BigDecimal("45.50"));
        assertThat(valor(ColumnasExportacion.RESERVAS, "Precio por confirmar", porConfirmar)).isEqualTo(true);
        assertThat(valor(ColumnasExportacion.RESERVAS, "Estado", confirmada)).isEqualTo(EstadoReserva.CONFIRMADA);
        assertThat(valor(ColumnasExportacion.RESERVAS, "Vence", confirmada)).isNull();
    }

    @Test
    void laRecepcionMuestraLaPlacaDeSuUnidad() {
        UUID unidad = UUID.randomUUID();
        var recepcion = new RecepcionPlantaResponse(UUID.randomUUID(), LocalDate.of(2026, 10, 22), "MANANA", unidad,
                new BigDecimal("100.00"), new BigDecimal("98.50"), new BigDecimal("-1.50"), "OK", null, List.of(), null, null,
                new BigDecimal("-1.50"), null);
        var columnas = ColumnasExportacion.recepciones(Map.of(unidad, "ABC-123"));

        assertThat(valor(columnas, "Unidad", recepcion)).isEqualTo("ABC-123");
        assertThat(valor(columnas, "Diferencia (L)", recepcion)).isEqualTo(new BigDecimal("-1.50"));
        assertThat(valor(columnas, "Observación", recepcion)).isNull();
        // Una unidad que ya no esta en el mapa deja la celda vacia en vez de fallar.
        assertThat(valor(ColumnasExportacion.recepciones(Map.of()), "Unidad", recepcion)).isNull();
    }

    @Test
    void preciosPorPresentacionTienenTresColumnasPorModalidadYToleranModalidadesAusentes() {
        var fila = new FilaPrecioPresentacionResponse(UUID.randomUUID(), "Queso", UUID.randomUUID(), "Queso fresco", UUID.randomUUID(), "1 kg",
                new PrecioModalidadResponse("VIGENTE", new BigDecimal("20.00"), LocalDate.of(2026, 10, 1)),
                null,
                new PrecioModalidadResponse("SIN_PRECIO", null, null), java.util.Map.of());

        assertThat(ColumnasExportacion.PRECIOS_PRESENTACION).hasSize(3 + 3 * 3);
        assertThat(valor(ColumnasExportacion.PRECIOS_PRESENTACION, "Mayorista", fila)).isEqualTo(new BigDecimal("20.00"));
        assertThat(valor(ColumnasExportacion.PRECIOS_PRESENTACION, "Mayorista: situación", fila)).isEqualTo("VIGENTE");
        assertThat(valor(ColumnasExportacion.PRECIOS_PRESENTACION, "Proveedor", fila)).isNull();
        assertThat(valor(ColumnasExportacion.PRECIOS_PRESENTACION, "Público: situación", fila)).isEqualTo("SIN_PRECIO");
        assertThat(valores(ColumnasExportacion.PRECIOS_PRESENTACION, fila)).hasSize(12);
    }

    @Test
    void laExportacionDePreciosTraeUnaTriadaPorCadaTipoDelCatalogoConLosOriginalesPrimero() {
        var tipos = java.util.List.of(
                tipo("DISTRIBUIDOR", "Distribuidor"), tipo("PUBLICO", "Público"), tipo("MAYORISTA", "Mayorista"), tipo("PROVEEDOR", "Proveedor"));
        var columnas = ColumnasExportacion.preciosPresentacion(tipos);
        var fila = new FilaPrecioPresentacionResponse(UUID.randomUUID(), "Queso", UUID.randomUUID(), "Queso fresco", UUID.randomUUID(), "1 kg", null, null, null,
                java.util.Map.of("PUBLICO", new PrecioModalidadResponse("VIGENTE", new BigDecimal("15.00"), LocalDate.of(2026, 10, 1)),
                        "DISTRIBUIDOR", new PrecioModalidadResponse("VIGENTE", new BigDecimal("9.00"), LocalDate.of(2026, 10, 2))));

        assertThat(columnas).hasSize(3 + 4 * 3);
        assertThat(columnas.stream().map(c -> c.titulo()).toList().subList(3, 6)).containsExactly("Mayorista", "Mayorista: vigente desde", "Mayorista: situación");   // el archivo de siempre no cambia
        assertThat(valor(columnas, "Público", fila)).isEqualTo(new BigDecimal("15.00"));
        assertThat(valor(columnas, "Distribuidor", fila)).isEqualTo(new BigDecimal("9.00"));            // el tipo nuevo, sin tocar el codigo
        assertThat(valor(columnas, "Mayorista", fila)).isNull();                                           // sin precio: celda vacia, no rompe
        assertThat(columnas.stream().map(c -> c.titulo()).toList().get(14)).isEqualTo("Distribuidor: situación");
    }

    private static com.planta.acopio.dto.TipoPrecioDtos.TipoPrecioResponse tipo(String codigo, String nombre) {
        return new com.planta.acopio.dto.TipoPrecioDtos.TipoPrecioResponse(codigo, nombre, "", true, false, com.planta.acopio.entity.ReglaComprador.CUALQUIERA, null);
    }

    @Test
    void unidadesResuelvenLaZonaYToleranUnidadesSinZona() {
        UUID zona = UUID.randomUUID();
        var conZona = new UnidadResponse(UUID.randomUUID(), "ABC-123", new BigDecimal("2.5"), zona, UUID.randomUUID(), "Pedro");
        var sinZona = new UnidadResponse(UUID.randomUUID(), "XYZ-999", null, null, UUID.randomUUID(), "Juan");
        var columnas = ColumnasExportacion.unidades(Map.of(zona, "Zona Norte"));

        assertThat(valores(columnas, conZona)).containsExactly("ABC-123", new BigDecimal("2.5"), "Zona Norte", "Pedro");
        assertThat(valores(columnas, sinZona)).containsExactly("XYZ-999", null, null, "Juan");
    }

    @Test
    void productosYRecetasResumenSusDetallesYToleranListasAusentes() {
        var presentaciones = List.of(
                new PresentacionResponse(UUID.randomUUID(), UUID.randomUUID(), "1 kg", new BigDecimal("1"), "KG", "pieza", true, null),
                new PresentacionResponse(UUID.randomUUID(), UUID.randomUUID(), "500 g", new BigDecimal("500"), "G", "bolsa", true, null));
        var producto = new ProductoResponse(UUID.randomUUID(), "Queso fresco", UUID.randomUUID(), "Queso", new BigDecimal("12.5"), "RAPIDO", true,
                presentaciones, null, null);
        var sinPresentaciones = new ProductoResponse(UUID.randomUUID(), "Yogurt", UUID.randomUUID(), "Yogurt", null, null, true, null, null, null);

        assertThat(valor(ColumnasExportacion.PRODUCTOS, "Presentaciones", producto)).isEqualTo("1 kg; 500 g");
        assertThat(valor(ColumnasExportacion.PRODUCTOS, "Presentaciones", sinPresentaciones)).isNull();

        var receta = new RecetaResponse(UUID.randomUUID(), "Fresco 100", UUID.randomUUID(), "Queso fresco", UUID.randomUUID(), "1 kg", "pieza",
                new BigDecimal("100"), new BigDecimal("12"), true, List.of(
                new RecetaResponse.Insumo(UUID.randomUUID(), "Cuajo", "ml", new BigDecimal("5.00"), true),
                new RecetaResponse.Insumo(UUID.randomUUID(), "Sal", "kg", new BigDecimal("0.50"), true)));
        var sinInsumos = new RecetaResponse(UUID.randomUUID(), "Vacía", UUID.randomUUID(), "Queso", UUID.randomUUID(), "1 kg", "pieza",
                new BigDecimal("100"), new BigDecimal("12"), false, null);

        assertThat(valor(ColumnasExportacion.RECETAS, "Insumos por leche base", receta)).isEqualTo("Cuajo 5 ml; Sal 0.5 kg");
        assertThat(valor(ColumnasExportacion.RECETAS, "Insumos por leche base", sinInsumos)).isNull();
    }
}
