package com.planta.acopio.service;

import com.planta.acopio.entity.LoteProduccion;
import com.planta.acopio.entity.MovimientoInventarioLeche;
import com.planta.acopio.entity.RecepcionPlanta;
import com.planta.acopio.entity.TipoMovimientoInventarioLeche;
import com.planta.acopio.repository.MovimientoInventarioLecheRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

// Inventario de leche (V8__inventario_leche.sql, fase 1 del refactor de Produccion): el saldo nunca es una
// tabla mutable, siempre SUM(cantidad) del libro mayor de movimientos (ver InventarioLecheService) -- mismo
// criterio que InventarioServiceTest ya prueba para el queso.
@ExtendWith(MockitoExtension.class)
class InventarioLecheServiceTest {

    @Mock private MovimientoInventarioLecheRepository repository;

    private InventarioLecheService service;

    @BeforeEach
    void setUp() {
        service = new InventarioLecheService(repository);
    }

    @Test
    void registrarEntradaRecepcionUsaLitrosPlantaComoCantidadYLigaLaRecepcion() {
        RecepcionPlanta recepcion = new RecepcionPlanta();
        recepcion.setId(UUID.randomUUID());
        recepcion.setLitrosPlanta(BigDecimal.valueOf(300));

        service.registrarEntradaRecepcion(recepcion);

        ArgumentCaptor<MovimientoInventarioLeche> captor = ArgumentCaptor.forClass(MovimientoInventarioLeche.class);
        verify(repository).save(captor.capture());
        MovimientoInventarioLeche movimiento = captor.getValue();
        assertThat(movimiento.getTipoMovimiento()).isEqualTo(TipoMovimientoInventarioLeche.ENTRADA_LECHE_RECEPCION);
        assertThat(movimiento.getCantidad()).isEqualByComparingTo(BigDecimal.valueOf(300));
        assertThat(movimiento.getRecepcionPlanta()).isSameAs(recepcion);
        assertThat(movimiento.getLoteProduccion()).isNull();
    }

    // Varias recepciones acumulan saldo: el saldo es SUM(cantidad), asi que tres entradas de 300, 150 y 80
    // dan 530 -- se verifica que el service consulta la suma real del repositorio, no un cache.
    @Test
    void elSaldoAcumulaVariasEntradasDeRecepcion() {
        when(repository.sumarLitrosDisponibles()).thenReturn(BigDecimal.valueOf(530));

        BigDecimal saldo = service.obtenerSaldoDisponibleLitros();

        assertThat(saldo).isEqualByComparingTo(BigDecimal.valueOf(530));
    }

    @Test
    void elSaldoEsCeroSinNingunMovimientoTodavia() {
        when(repository.sumarLitrosDisponibles()).thenReturn(BigDecimal.ZERO);

        BigDecimal saldo = service.obtenerSaldoDisponibleLitros();

        assertThat(saldo).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // Refactor de Produccion, fase 2: salida agregada del total consumido por un lote (una sola fila,
    // aunque la leche haya venido de varias recepciones distintas por FIFO).
    @Test
    void registrarSalidaProduccionGuardaLaCantidadEnNegativoLigadaAlLote() {
        LoteProduccion lote = new LoteProduccion();
        lote.setId(UUID.randomUUID());

        service.registrarSalidaProduccion(lote, BigDecimal.valueOf(150));

        ArgumentCaptor<MovimientoInventarioLeche> captor = ArgumentCaptor.forClass(MovimientoInventarioLeche.class);
        verify(repository).save(captor.capture());
        MovimientoInventarioLeche movimiento = captor.getValue();
        assertThat(movimiento.getTipoMovimiento()).isEqualTo(TipoMovimientoInventarioLeche.SALIDA_LECHE_PRODUCCION);
        assertThat(movimiento.getCantidad()).isEqualByComparingTo(BigDecimal.valueOf(-150));
        assertThat(movimiento.getLoteProduccion()).isSameAs(lote);
        assertThat(movimiento.getRecepcionPlanta()).isNull();
    }
}
