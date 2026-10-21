package com.planta.acopio.service;

import com.planta.acopio.dto.KardexResponse;
import com.planta.acopio.dto.MovimientoKardexResponse;
import com.planta.acopio.entity.Presentacion;
import com.planta.acopio.entity.Producto;
import com.planta.acopio.exception.RecursoNoEncontradoException;
import com.planta.acopio.exception.ValidacionNegocioException;
import com.planta.acopio.repository.InsumoRepository;
import com.planta.acopio.repository.MovimientoInsumoRepository;
import com.planta.acopio.repository.MovimientoInventarioLecheRepository;
import com.planta.acopio.repository.MovimientoInventarioRepository;
import com.planta.acopio.repository.PresentacionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

// V19: armado del kardex a partir de las filas de las consultas nativas (el SQL se verifica contra Postgres en
// KardexIntegrationTest). Aqui: rango de fechas, totales del rango, signo de entrada/salida, tipos del driver.
@ExtendWith(MockitoExtension.class)
class KardexServiceTest {

    @Mock private MovimientoInventarioLecheRepository lecheRepository;
    @Mock private MovimientoInsumoRepository insumoMovRepository;
    @Mock private MovimientoInventarioRepository productoMovRepository;
    @Mock private InsumoRepository insumoRepository;
    @Mock private PresentacionRepository presentacionRepository;

    private KardexService service;
    private final PageRequest pagina = PageRequest.of(0, 50);

    @BeforeEach
    void setUp() {
        service = new KardexService(lecheRepository, insumoMovRepository, productoMovRepository, insumoRepository,
                presentacionRepository);
    }

    private static Object[] fila(String tipo, Object cantidad, Object saldo, String docTipo) {
        return new Object[]{UUID.randomUUID(), Timestamp.valueOf("2026-10-01 08:30:00"), tipo, cantidad, saldo,
                docTipo, UUID.randomUUID(), Date.valueOf("2026-10-01"), "ref", "Ana", null};
    }

    @Test
    void elRangoEsInclusivoPorDiaCompletoYRechazaDesdePosteriorAHasta() {
        KardexService.Rango r = KardexService.rango(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));
        assertThat(r.desde()).isEqualTo(LocalDateTime.of(2026, 10, 1, 0, 0));
        assertThat(r.hasta()).isEqualTo(LocalDateTime.of(2026, 11, 1, 0, 0)); // hasta inclusive
        assertThat(KardexService.rango(null, null)).isEqualTo(new KardexService.Rango(null, null));
        assertThatThrownBy(() -> KardexService.rango(LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 1)))
                .isInstanceOf(ValidacionNegocioException.class);
    }

    @Test
    void kardexDeLecheTotalizaElRangoYSeparaEntradasDeSalidas() {
        when(lecheRepository.resumen(any(), any())).thenReturn(List.<Object[]>of(new Object[]{
                new BigDecimal("500"), new BigDecimal("1500"), new BigDecimal("1000")}));
        when(lecheRepository.kardex(any(), any(), any())).thenReturn(new PageImpl<>(List.of(
                fila("ENTRADA_LECHE_RECEPCION", new BigDecimal("1500"), new BigDecimal("2000"), "RECEPCION"),
                fila("SALIDA_LECHE_PRODUCCION", new BigDecimal("-1000"), new BigDecimal("1000"), "LOTE")), pagina, 2));

        KardexResponse k = service.leche(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 1), pagina);

        assertThat(k.tipoItem()).isEqualTo("LECHE");
        assertThat(k.itemId()).isNull();
        assertThat(k.unidadMedida()).isEqualTo("L");
        assertThat(k.saldoInicial()).isEqualByComparingTo("500");
        assertThat(k.totalEntradas()).isEqualByComparingTo("1500");
        assertThat(k.totalSalidas()).isEqualByComparingTo("1000");
        assertThat(k.saldoFinal()).isEqualByComparingTo("1000");
        List<MovimientoKardexResponse> m = k.movimientos().contenido();
        assertThat(m.get(0).entrada()).isEqualByComparingTo("1500");
        assertThat(m.get(0).salida()).isNull();
        assertThat(m.get(1).entrada()).isNull();
        assertThat(m.get(1).salida()).isEqualByComparingTo("1000"); // salida positiva
        assertThat(m.get(1).saldo()).isEqualByComparingTo("1000");
        assertThat(m.get(0).fecha()).isEqualTo(LocalDateTime.of(2026, 10, 1, 8, 30));
        assertThat(m.get(0).documentoFecha()).isEqualTo(LocalDate.of(2026, 10, 1));
        verify(lecheRepository).resumen(LocalDateTime.of(2026, 10, 1, 0, 0), LocalDateTime.of(2026, 10, 2, 0, 0));
    }

    @Test
    void kardexDeProductoNormalizaEnterosDelDriverYNombraProductoYPresentacion() {
        Producto andino = new Producto();
        andino.setNombre("Queso Andino");
        Presentacion pieza = new Presentacion();
        pieza.setId(UUID.randomUUID());
        pieza.setNombre("Pieza");
        pieza.setUnidadConteo("pieza");
        pieza.setProducto(andino);
        when(presentacionRepository.findById(pieza.getId())).thenReturn(Optional.of(pieza));
        // int y bigint tal como los devuelve el driver para INT y SUM(INT).
        when(productoMovRepository.resumen(any(), any(), any())).thenReturn(List.<Object[]>of(new Object[]{0L, 9L, 2L}));
        when(productoMovRepository.kardex(any(), any(), any(), any())).thenReturn(new PageImpl<>(List.of(
                fila("ENTRADA_PRODUCCION", 9, 9L, "LOTE"), fila("SALIDA_VENTA", -2, 7L, "VENTA")), pagina, 2));

        KardexResponse k = service.presentacion(pieza.getId(), null, null, pagina);

        assertThat(k.itemNombre()).isEqualTo("Queso Andino · Pieza");
        assertThat(k.unidadMedida()).isEqualTo("pieza");
        assertThat(k.saldoFinal()).isEqualByComparingTo("7");
        assertThat(k.movimientos().contenido().get(1).salida()).isEqualByComparingTo("2");
        assertThat(k.movimientos().contenido().get(1).saldo()).isEqualByComparingTo("7");
        assertThat(k.movimientos().totalElementos()).isEqualTo(2);
    }

    @Test
    void itemInexistenteEs404() {
        UUID id = UUID.randomUUID();
        when(insumoRepository.findById(id)).thenReturn(Optional.empty());
        when(presentacionRepository.findById(id)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.insumo(id, null, null, pagina)).isInstanceOf(RecursoNoEncontradoException.class);
        assertThatThrownBy(() -> service.presentacion(id, null, null, pagina)).isInstanceOf(RecursoNoEncontradoException.class);
    }

    @Test
    void conversionDeTiposDelDriver() {
        assertThat(KardexService.decimal(null)).isEqualByComparingTo("0");
        assertThat(KardexService.decimal(7)).isEqualByComparingTo("7");
        assertThat(KardexService.fechaHora(LocalDateTime.of(2026, 1, 2, 3, 4))).isEqualTo(LocalDateTime.of(2026, 1, 2, 3, 4));
        assertThat(KardexService.fecha(LocalDate.of(2026, 1, 2))).isEqualTo(LocalDate.of(2026, 1, 2));
        assertThat(KardexService.fecha(null)).isNull();
    }
}
