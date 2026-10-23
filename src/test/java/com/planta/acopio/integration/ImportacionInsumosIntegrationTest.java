package com.planta.acopio.integration;

import com.planta.acopio.entity.Insumo;
import com.planta.acopio.entity.MovimientoInsumo;
import com.planta.acopio.entity.TipoMovimientoInsumo;
import com.planta.acopio.exception.ConflictoNegocioException;
import com.planta.acopio.importacion.ExcelDePrueba;
import com.planta.acopio.importacion.ImportacionService;
import com.planta.acopio.importacion.ResultadoImportacionResponse;
import com.planta.acopio.importacion.VistaPreviaImportacionResponse;
import com.planta.acopio.repository.InsumoRepository;
import com.planta.acopio.repository.MovimientoInsumoRepository;
import com.planta.acopio.service.InsumoService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doCallRealMethod;

/**
 * Importacion de insumos contra PostgreSQL real: el stock inicial entra por el servicio de negocio como SALDO_INICIAL del
 * kardex, un insumo existente no se toca, y un fallo a mitad no deja insumos ni movimientos sueltos.
 */
@SpringBootTest
class ImportacionInsumosIntegrationTest {

    @DynamicPropertySource
    static void configurarDatasource(DynamicPropertyRegistry registry) {
        PostgresDePrueba.registrar(registry);
    }

    private static final AtomicInteger PRUEBAS = new AtomicInteger(5000);

    @Autowired private ImportacionService importacionService;
    @Autowired private InsumoRepository insumoRepository;
    @Autowired private MovimientoInsumoRepository movimientoRepository;
    @SpyBean private InsumoService insumoService;

    private String sufijo;
    private byte[] plantillaBytes;

    @BeforeEach
    void preparar() {
        sufijo = String.valueOf(PRUEBAS.incrementAndGet());
        Mockito.reset(insumoService);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("admin@planta.com", null, List.of()));
        plantillaBytes = ExcelDePrueba.bytes(importacionService.plantilla("insumos"));
    }

    @AfterEach
    void limpiar() {
        SecurityContextHolder.clearContext();
    }

    private VistaPreviaImportacionResponse previsualizar(List<List<Object>> filas) {
        return importacionService.previsualizar("insumos", new MockMultipartFile("archivo", "insumos.xlsx", "application/octet-stream",
                ExcelDePrueba.conFilas(plantillaBytes, filas)));
    }

    private Insumo insumo(String nombre) {
        return insumoRepository.findAll().stream().filter(i -> i.getNombre().equals(nombre + " " + sufijo)).findFirst().orElseThrow();
    }

    private long insumosConSufijo() {
        return insumoRepository.findAll().stream().filter(i -> i.getNombre().endsWith(sufijo)).count();
    }

    private List<MovimientoInsumo> movimientos(Insumo insumo) {
        return movimientoRepository.findAll().stream().filter(m -> m.getInsumo().getId().equals(insumo.getId())).toList();
    }

    @Test
    void laVistaPreviaNoCreaInsumosNiMovimientos() {
        long insumos = insumoRepository.count();
        long movimientos = movimientoRepository.count();

        VistaPreviaImportacionResponse vista = previsualizar(List.of(
                List.of("Cuajo " + sufijo, "ml", "100"), List.of("Sal " + sufijo, "kg", "")));

        assertThat(vista.validas()).isEqualTo(2);
        assertThat(insumoRepository.count()).isEqualTo(insumos);
        assertThat(movimientoRepository.count()).isEqualTo(movimientos);
    }

    @Test
    void elStockInicialQuedaComoSaldoInicialEnElKardexYElStockCuadraConSusMovimientos() {
        VistaPreviaImportacionResponse vista = previsualizar(List.of(
                List.of("Cuajo " + sufijo, "ml", "250.5"),
                List.of("Sal " + sufijo, "kg", "1250,75"),
                List.of("Fermento " + sufijo, "g", ""),                  // sin stock: se crea en 0 y sin movimiento
                java.util.Arrays.<Object>asList("Numero " + sufijo, "u", 40.0)));

        ResultadoImportacionResponse resultado = importacionService.confirmar(vista.id());

        assertThat(resultado.creadas()).isEqualTo(4);
        Insumo cuajo = insumo("Cuajo");
        assertThat(cuajo.getStock()).isEqualByComparingTo("250.50");
        assertThat(cuajo.getUnidadMedida()).isEqualTo("ml");
        assertThat(cuajo.getActivo()).isTrue();
        assertThat(movimientos(cuajo)).hasSize(1).first().satisfies(m -> {
            assertThat(m.getTipoMovimiento()).isEqualTo(TipoMovimientoInsumo.SALDO_INICIAL);
            assertThat(m.getCantidad()).isEqualByComparingTo("250.50");
            assertThat(m.getUsuario().getEmail()).isEqualTo("admin@planta.com");
            assertThat(m.getMotivo()).contains("inicial");
        });
        assertThat(insumo("Sal").getStock()).isEqualByComparingTo("1250.75");
        assertThat(insumo("Numero").getStock()).isEqualByComparingTo("40.00");

        Insumo fermento = insumo("Fermento");
        assertThat(fermento.getStock()).isEqualByComparingTo("0");
        assertThat(movimientos(fermento)).isEmpty();

        // Invariante del kardex: el stock de cada insumo es la suma de sus movimientos.
        for (String nombre : List.of("Cuajo", "Sal", "Fermento", "Numero")) {
            Insumo i = insumo(nombre);
            assertThat(movimientoRepository.sumarCantidad(i.getId())).as(nombre).isEqualByComparingTo(i.getStock());
        }
    }

    @Test
    void unNombreExistenteSeOmiteYSuStockNoCambia() {
        Insumo previo = new Insumo();
        previo.setNombre("Existente " + sufijo);
        previo.setUnidadMedida("kg");
        previo.setStock(new BigDecimal("7.00"));
        previo.setActivo(false); // aun desactivado cuenta como existente
        insumoRepository.save(previo);

        VistaPreviaImportacionResponse vista = previsualizar(List.of(
                List.of("EXISTENTE " + sufijo, "g", "999"),     // mismo nombre sin distinguir mayusculas
                List.of("Nuevo " + sufijo, "g", "5")));
        ResultadoImportacionResponse resultado = importacionService.confirmar(vista.id());

        assertThat(resultado.creadas()).isEqualTo(1);
        assertThat(resultado.duplicadas()).isEqualTo(1);
        Insumo despues = insumoRepository.findById(previo.getId()).orElseThrow();
        assertThat(despues.getStock()).isEqualByComparingTo("7.00");
        assertThat(despues.getUnidadMedida()).isEqualTo("kg");
        assertThat(despues.getActivo()).isFalse();
        assertThat(movimientos(despues)).isEmpty();
        assertThat(insumosConSufijo()).isEqualTo(2);
    }

    @Test
    void repetidoEnElArchivoSoloCreaElPrimeroConsuStock() {
        VistaPreviaImportacionResponse vista = previsualizar(List.of(
                List.of("Cuajo " + sufijo, "ml", "10"),
                List.of("cuajo " + sufijo, "ml", "99")));
        importacionService.confirmar(vista.id());

        assertThat(insumosConSufijo()).isEqualTo(1);
        assertThat(insumo("Cuajo").getStock()).isEqualByComparingTo("10.00");
        assertThat(movimientos(insumo("Cuajo"))).hasSize(1);
    }

    @Test
    void lasFilasInvalidasNoCreanNadaYSeConfirmaElResto() {
        VistaPreviaImportacionResponse vista = previsualizar(List.of(
                List.of("Bueno " + sufijo, "kg", "3"),
                List.of("Stock negativo " + sufijo, "kg", "-3"),
                List.of("Sin unidad " + sufijo, "", "3"),
                List.of("Decimales " + sufijo, "kg", "1.234")));

        assertThat(vista.validas()).isEqualTo(1);
        assertThat(vista.invalidas()).isEqualTo(3);
        ResultadoImportacionResponse resultado = importacionService.confirmar(vista.id());

        assertThat(resultado.creadas()).isEqualTo(1);
        assertThat(insumosConSufijo()).isEqualTo(1);
    }

    @Test
    void unFalloAMitadRevierteLosInsumosYSusMovimientos() {
        VistaPreviaImportacionResponse vista = previsualizar(List.of(
                List.of("Uno " + sufijo, "kg", "1"), List.of("Dos " + sufijo, "kg", "2"), List.of("Tres " + sufijo, "kg", "3")));
        long movimientos = movimientoRepository.count();
        doCallRealMethod().doCallRealMethod().doThrow(new IllegalStateException("fallo interno")).when(insumoService).crear(any());

        assertThatThrownBy(() -> importacionService.confirmar(vista.id()))
                .isInstanceOf(ConflictoNegocioException.class).hasMessageContaining("No se creó ningún registro").hasMessageContaining("fila 4");

        assertThat(insumosConSufijo()).isZero();
        assertThat(movimientoRepository.count()).isEqualTo(movimientos); // ni un saldo inicial suelto
        Mockito.reset(insumoService);
        assertThat(importacionService.confirmar(vista.id()).creadas()).isEqualTo(3);
        assertThat(insumosConSufijo()).isEqualTo(3);
    }

    @Test
    void confirmarDosVecesNoDuplicaInsumosNiMovimientos() {
        VistaPreviaImportacionResponse vista = previsualizar(List.of(List.of("Unico " + sufijo, "kg", "5")));

        importacionService.confirmar(vista.id());
        ResultadoImportacionResponse repetida = importacionService.confirmar(vista.id());

        assertThat(repetida.yaConfirmada()).isTrue();
        assertThat(insumosConSufijo()).isEqualTo(1);
        assertThat(movimientos(insumo("Unico"))).hasSize(1);
    }

    @Test
    void variasFilasMuchasConStockSeCreanTodasConSuKardex() {
        List<List<Object>> filas = new ArrayList<>();
        for (int i = 1; i <= 60; i++) filas.add(List.of("Masivo " + i + " " + sufijo, "u", String.valueOf(i)));
        VistaPreviaImportacionResponse vista = previsualizar(filas);

        assertThat(importacionService.confirmar(vista.id()).creadas()).isEqualTo(60);

        assertThat(insumosConSufijo()).isEqualTo(60);
        long conMovimiento = insumoRepository.findAll().stream().filter(i -> i.getNombre().endsWith(sufijo))
                .filter(i -> movimientoRepository.sumarCantidad(i.getId()).compareTo(i.getStock()) == 0).count();
        assertThat(conMovimiento).isEqualTo(60);
    }
}
