package com.planta.acopio.service;

import com.planta.acopio.dto.InsumoRequest;
import com.planta.acopio.entity.Insumo;
import com.planta.acopio.exception.RecursoNoEncontradoException;
import com.planta.acopio.repository.InsumoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

// Mismo shape/patron de pruebas que ProductoService (catalogo administrable, sin flujo de negocio propio).
@ExtendWith(MockitoExtension.class)
class InsumoServiceTest {

    @Mock private InsumoRepository repository;
    @Mock private InventarioInsumoService inventarioInsumoService;

    private InsumoService service;
    private final UUID insumoId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new InsumoService(repository, inventarioInsumoService);
    }

    @Test
    void crearGuardaConActivoTruePorDefecto() {
        when(repository.save(org.mockito.ArgumentMatchers.any())).thenAnswer(inv -> inv.getArgument(0));

        var respuesta = service.crear(new InsumoRequest("Cuajo", "unidades"));

        assertThat(respuesta.nombre()).isEqualTo("Cuajo");
        assertThat(respuesta.unidadMedida()).isEqualTo("unidades");
        assertThat(respuesta.activo()).isTrue();
    }

    @Test
    void desactivarPonenActivoEnFalse() {
        Insumo insumo = insumoConId();
        when(repository.findById(insumoId)).thenReturn(Optional.of(insumo));

        service.desactivar(insumoId);

        assertThat(insumo.getActivo()).isFalse();
    }

    @Test
    void reactivarPonenActivoEnTrue() {
        Insumo insumo = insumoConId();
        insumo.setActivo(false);
        when(repository.findById(insumoId)).thenReturn(Optional.of(insumo));

        var respuesta = service.reactivar(insumoId);

        assertThat(respuesta.activo()).isTrue();
    }

    @Test
    void obtenerLanzaSiNoExiste() {
        when(repository.findById(insumoId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.obtener(insumoId))
                .isInstanceOf(RecursoNoEncontradoException.class);
    }

    // ---- V19 (kardex): el stock nace como SALDO_INICIAL y ya no se edita desde el catalogo ----

    @Test
    void crearRegistraElStockInicialComoPrimerMovimientoDelKardex() {
        when(repository.save(org.mockito.ArgumentMatchers.any())).thenAnswer(inv -> inv.getArgument(0));

        service.crear(new InsumoRequest("Cuajo", "g", new java.math.BigDecimal("15")));

        org.mockito.Mockito.verify(inventarioInsumoService).registrarSaldoInicial(
                org.mockito.ArgumentMatchers.argThat(i -> i.getNombre().equals("Cuajo")),
                org.mockito.ArgumentMatchers.eq(new java.math.BigDecimal("15")));
    }

    @Test
    void actualizarRechazaCambiarElStockSinMovimiento() {
        Insumo insumo = insumoConId();
        insumo.setStock(new java.math.BigDecimal("20"));
        when(repository.findById(insumoId)).thenReturn(Optional.of(insumo));

        assertThatThrownBy(() -> service.actualizar(insumoId, new InsumoRequest("Sal", "kg", new java.math.BigDecimal("5"))))
                .isInstanceOf(com.planta.acopio.exception.ValidacionNegocioException.class)
                .hasMessageContaining("ingreso o un ajuste");
        assertThat(insumo.getStock()).isEqualByComparingTo("20");
        assertThat(insumo.getUnidadMedida()).isEqualTo("sacos");
    }

    @Test
    void actualizarAceptaElMismoStockONuloYSoloCambiaNombreYUnidad() {
        Insumo insumo = insumoConId();
        insumo.setStock(new java.math.BigDecimal("20"));
        when(repository.findById(insumoId)).thenReturn(Optional.of(insumo));

        service.actualizar(insumoId, new InsumoRequest("Sal fina", "kg", new java.math.BigDecimal("20.00")));
        service.actualizar(insumoId, new InsumoRequest("Sal fina", "kg"));

        assertThat(insumo.getNombre()).isEqualTo("Sal fina");
        assertThat(insumo.getUnidadMedida()).isEqualTo("kg");
        assertThat(insumo.getStock()).isEqualByComparingTo("20");
    }

    private Insumo insumoConId() {
        Insumo i = new Insumo();
        i.setId(insumoId);
        i.setNombre("Sal");
        i.setUnidadMedida("sacos");
        return i;
    }
}
