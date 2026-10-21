package com.planta.acopio.service;

import com.planta.acopio.dto.RegistroAcopioDTO;
import com.planta.acopio.dto.RegistroAcopioResponse;
import com.planta.acopio.dto.SyncResultResponse;
import com.planta.acopio.dto.VentaRequest;
import com.planta.acopio.dto.VentaResponse;
import com.planta.acopio.exception.RecursoNoEncontradoException;
import com.planta.acopio.repository.*;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

// CORE_DESIGN.md §16, prioridad 2: la sincronizacion batch NO debe ser
// todo-o-nada -- un item invalido o con error de negocio se reporta como
// error individual y el resto del lote se procesa igual.
@ExtendWith(MockitoExtension.class)
class SyncServiceTest {

    @Mock private RegistroAcopioService registroAcopioService;
    @Mock private AnalisisCalidadService analisisCalidadService;
    @Mock private LoteProduccionService loteProduccionService;
    @Mock private VentaService ventaService;
    @Mock private JornadaService jornadaService;
    @Mock private ConciliacionService conciliacionService;
    @Mock private PuntoGpsJornadaService puntoGpsJornadaService;
    @Mock private ProveedorService proveedorService;
    @Mock private PrecioLitroRepository precioLitroRepository;
    @Mock private ComunicadoRepository comunicadoRepository;
    @Mock private PrediccionProveedorRepository prediccionProveedorRepository;
    @Mock private MotivoObservacionRepository motivoObservacionRepository;
    @Mock private ProductoRepository productoRepository;
    @Mock private PresentacionRepository presentacionRepository;
    @Mock private UnidadRepository unidadRepository;
    @Mock private InsumoRepository insumoRepository;

    private SyncService service;
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @BeforeEach
    void setUp() {
        service = new SyncService(registroAcopioService, analisisCalidadService, loteProduccionService,
                ventaService, jornadaService, conciliacionService, puntoGpsJornadaService, proveedorService,
                precioLitroRepository, comunicadoRepository, prediccionProveedorRepository,
                motivoObservacionRepository, productoRepository, presentacionRepository, unidadRepository, insumoRepository, validator);
    }

    @Test
    void unItemConErrorDeNegocioNoAbortaElRestoDelLote() {
        var item1 = dtoValido("uuid-1");
        var item2 = dtoValido("uuid-2"); // este fallara por proveedor inexistente
        var item3 = dtoValido("uuid-3");

        // DATA-014: `procesarLote` ahora SI lee el id real de la respuesta (antes se
        // descartaba sin tocarlo) -- un `null` aca ya no sirve de estubeo generico,
        // rompería con NullPointerException al llamar `.id()`. Se estubea primero el
        // caso general (item1/item3) con una respuesta real y luego se sobreescribe
        // item2 con la excepcion -- con Mockito en modo strict stubs, invocar el mock
        // con argumentos no estubeados cuando SI existe un stub especifico para otros
        // argumentos del mismo metodo lanza PotentialStubbingProblem.
        when(registroAcopioService.registrarOIgnorarSiDuplicado(any())).thenReturn(respuestaAcopio(UUID.randomUUID()));
        when(registroAcopioService.registrarOIgnorarSiDuplicado(item2))
                .thenThrow(new RecursoNoEncontradoException("Proveedor no encontrado"));

        var resultado = service.procesarLoteRegistrosAcopio(List.of(item1, item2, item3));

        assertThat(resultado.confirmados()).containsExactlyInAnyOrder("uuid-1", "uuid-3");
        assertThat(resultado.errores()).hasSize(1);
        assertThat(resultado.errores().get(0).uuidCliente()).isEqualTo("uuid-2");

        // Los 3 items se intentaron -- el fallo del item 2 no impidio procesar el 3.
        verify(registroAcopioService, times(3)).registrarOIgnorarSiDuplicado(any());
    }

    @Test
    void unItemConCamposInvalidosSeReportaSinLlegarAlService() {
        // litros negativo viola @DecimalMin("0.0") del DTO -- debe detectarse
        // ANTES de invocar el service (Bean Validation manual, no @Valid en el
        // Controller, que abortaria todo el lote).
        var itemInvalido = new RegistroAcopioDTO("uuid-invalido", UUID.randomUUID(), UUID.randomUUID(),
                LocalDateTime.now(), BigDecimal.valueOf(-5), null, null, null, false, null, null);

        var resultado = service.procesarLoteRegistrosAcopio(List.of(itemInvalido));

        assertThat(resultado.confirmados()).isEmpty();
        assertThat(resultado.errores()).hasSize(1);
        verify(registroAcopioService, never()).registrarOIgnorarSiDuplicado(any());
    }

    // DATA-014: un lote parcialmente exitoso debe devolver el id real SOLO para los
    // uuidCliente que de verdad se confirmaron -- nunca uno para el que fallo, y
    // nunca mas ids que confirmados (mismo tamano, mismo contenido de uuidCliente).
    @Test
    void unLoteParcialmenteExitosoDevuelveIdsSoloParaLosConfirmados() {
        var item1 = dtoValido("uuid-1");
        var item2 = dtoValido("uuid-2"); // fallara
        var item3 = dtoValido("uuid-3");
        UUID idReal1 = UUID.randomUUID();
        UUID idReal3 = UUID.randomUUID();

        when(registroAcopioService.registrarOIgnorarSiDuplicado(item1)).thenReturn(respuestaAcopio(idReal1));
        when(registroAcopioService.registrarOIgnorarSiDuplicado(item2))
                .thenThrow(new RecursoNoEncontradoException("Proveedor no encontrado"));
        when(registroAcopioService.registrarOIgnorarSiDuplicado(item3)).thenReturn(respuestaAcopio(idReal3));

        var resultado = service.procesarLoteRegistrosAcopio(List.of(item1, item2, item3));

        assertThat(resultado.confirmadosConId()).hasSize(2);
        assertThat(resultado.confirmadosConId())
                .extracting(SyncResultResponse.ConfirmacionItem::uuidCliente, SyncResultResponse.ConfirmacionItem::id)
                .containsExactlyInAnyOrder(tuple("uuid-1", idReal1), tuple("uuid-3", idReal3));
        assertThat(resultado.confirmadosConId().stream().map(SyncResultResponse.ConfirmacionItem::uuidCliente))
                .doesNotContain("uuid-2");
    }

    // DATA-014: reenviar el mismo uuidCliente (reintento, ej. la app murio a mitad de
    // vuelo y no llego a leer la primera respuesta) debe devolver el mismo id real --
    // nunca uno distinto -- porque el Service de cada recurso ya es idempotente por
    // uuidCliente (findByUuidCliente / existeRegistroYaVinculadoAOtroLote). Esta prueba
    // fija el estubeo para representar exactamente esa garantia y confirma que
    // `procesarLote` la deja pasar intacta hasta `confirmadosConId`.
    @Test
    void reenviarElMismoUuidClienteDevuelveElMismoIdReal() {
        var item = dtoValido("uuid-repetido");
        UUID idYaAsignado = UUID.randomUUID();
        when(registroAcopioService.registrarOIgnorarSiDuplicado(item)).thenReturn(respuestaAcopio(idYaAsignado));

        var primeraRespuesta = service.procesarLoteRegistrosAcopio(List.of(item));
        var segundaRespuesta = service.procesarLoteRegistrosAcopio(List.of(item)); // simula el reintento

        assertThat(primeraRespuesta.confirmadosConId().get(0).id()).isEqualTo(idYaAsignado);
        assertThat(segundaRespuesta.confirmadosConId().get(0).id()).isEqualTo(idYaAsignado);
    }

    // DATA-014: confirma que la simplificacion generica de `procesarLote` (de <T, R> a
    // <T>, `Function<T, UUID>`) funciona igual para un recurso con una forma de
    // respuesta distinta (VentaResponse), no solo para RegistroAcopioResponse.
    @Test
    void elIdRealTambienViajaParaVentas() {
        var itemVenta = new VentaRequest("uuid-venta-1", java.time.LocalDate.now(), "MAYORISTA",
                UUID.randomUUID(), 10, BigDecimal.TEN);
        UUID idReal = UUID.randomUUID();
        when(ventaService.registrarOIgnorarSiDuplicado(itemVenta)).thenReturn(respuestaVenta(idReal));

        var resultado = service.procesarLoteVentas(List.of(itemVenta));

        assertThat(resultado.confirmados()).containsExactly("uuid-venta-1");
        assertThat(resultado.confirmadosConId()).hasSize(1);
        assertThat(resultado.confirmadosConId().get(0).id()).isEqualTo(idReal);
    }

    // Fase 3 -- Recorrido GPS: confirma el wiring de procesarLotePuntosGps (PuntoGpsJornada.getId(), no
    // un record con .id()) contra el mismo motor generico -- mismo criterio que elIdRealTambienViajaParaVentas.
    @Test
    void elIdRealTambienViajaParaPuntosGps() {
        var itemPunto = new com.planta.acopio.dto.PuntoGpsJornadaDTO("uuid-punto-1", UUID.randomUUID(),
                BigDecimal.valueOf(-6.7), BigDecimal.valueOf(-78.5), null, LocalDateTime.now());
        UUID idReal = UUID.randomUUID();
        var puntoGuardado = new com.planta.acopio.entity.PuntoGpsJornada();
        puntoGuardado.setId(idReal);
        when(puntoGpsJornadaService.registrarOIgnorarSiDuplicado(itemPunto)).thenReturn(puntoGuardado);

        var resultado = service.procesarLotePuntosGps(List.of(itemPunto));

        assertThat(resultado.confirmados()).containsExactly("uuid-punto-1");
        assertThat(resultado.confirmadosConId().get(0).id()).isEqualTo(idReal);
    }

    private RegistroAcopioDTO dtoValido(String uuidCliente) {
        return new RegistroAcopioDTO(uuidCliente, UUID.randomUUID(), UUID.randomUUID(),
                LocalDateTime.now(), BigDecimal.valueOf(40), null, null, null, false, null, null);
    }

    /** Solo el `id` importa para estas pruebas -- el resto de los campos no participa de `procesarLote`. */
    private RegistroAcopioResponse respuestaAcopio(UUID id) {
        return new RegistroAcopioResponse(id, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    private VentaResponse respuestaVenta(UUID id) {
        return new VentaResponse(id, null, null, null, null, null, null, null, null);
    }
}
