package com.planta.acopio.service;

import com.planta.acopio.dto.DetalleSalidaRequest;
import com.planta.acopio.dto.DisponibilidadProduccionResponse;
import com.planta.acopio.dto.FinalizarLoteRequest;
import com.planta.acopio.dto.VencimientoSalidaRequest;
import com.planta.acopio.dto.LoteProduccionResponse;
import com.planta.acopio.dto.PlanificarLoteRequest;
import com.planta.acopio.entity.EstadoLote;
import com.planta.acopio.entity.Insumo;
import com.planta.acopio.entity.LoteProduccion;
import com.planta.acopio.entity.LoteProduccionDetalle;
import com.planta.acopio.entity.LoteProduccionInsumo;
import com.planta.acopio.entity.LoteProduccionRecepcionConsumo;
import com.planta.acopio.entity.Presentacion;
import com.planta.acopio.entity.Producto;
import com.planta.acopio.entity.RecepcionPlanta;
import com.planta.acopio.entity.Receta;
import com.planta.acopio.entity.RecetaInsumo;
import com.planta.acopio.entity.Usuario;
import com.planta.acopio.exception.ConflictoNegocioException;
import com.planta.acopio.exception.RecursoInactivoException;
import com.planta.acopio.exception.ValidacionNegocioException;
import com.planta.acopio.repository.InsumoRepository;
import com.planta.acopio.repository.LoteProduccionDetalleRepository;
import com.planta.acopio.repository.LoteProduccionInsumoRepository;
import com.planta.acopio.repository.LoteProduccionRecepcionConsumoRepository;
import com.planta.acopio.repository.LoteProduccionRepository;
import com.planta.acopio.repository.PresentacionRepository;
import com.planta.acopio.repository.ProductoRepository;
import com.planta.acopio.repository.RecepcionPlantaRepository;
import com.planta.acopio.repository.RecetaRepository;
import com.planta.acopio.repository.MovimientoInsumoRepository;
import com.planta.acopio.repository.UsuarioRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * V18: produccion planificada desde receta. Datos del requerimiento: Queso Andino, base 100 L + 1 g cuajo +
 * 1 kg sal -> 1 pieza; stock leche 1500 L, cuajo 15 g, sal 20 kg; produccion de 1000 L -> 10 esperadas,
 * 9 obtenidas. Repositorios en memoria (mocks con estado) para seguir el lote a traves de sus transiciones.
 * La atomicidad real (rollback, bloqueos, UPDATE condicionado) se verifica contra Postgres en
 * RecetaProduccionIntegrationTest.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LoteProduccionCicloVidaTest {

    @Mock private LoteProduccionRepository repository;
    @Mock private ProductoRepository productoRepository;
    @Mock private PresentacionRepository presentacionRepository;
    @Mock private UsuarioRepository usuarioRepository;
    @Mock private InventarioService inventarioService;
    @Mock private InventarioLecheService inventarioLecheService;
    @Mock private RecepcionPlantaRepository recepcionPlantaRepository;
    @Mock private LoteProduccionRecepcionConsumoRepository consumoRepository;
    @Mock private LoteProduccionDetalleRepository detalleRepository;
    @Mock private InsumoRepository insumoRepository;
    @Mock private LoteProduccionInsumoRepository loteInsumoRepository;
    @Mock private RecetaRepository recetaRepository;
    @Mock private MovimientoInsumoRepository movimientoInsumoRepository;

    private LoteProduccionService service;
    private Producto andino;
    private Presentacion pieza;
    private Insumo cuajo;
    private Insumo sal;
    private Receta receta;
    private RecepcionPlanta recepcion;

    private final Map<UUID, LoteProduccion> lotes = new HashMap<>();
    private final Map<UUID, List<LoteProduccionInsumo>> insumosPorLote = new HashMap<>();
    private final Map<UUID, List<LoteProduccionDetalle>> detallesPorLote = new HashMap<>();
    private final Map<UUID, BigDecimal> consumidoPorRecepcion = new HashMap<>();

    @BeforeEach
    void setUp() {
        service = new LoteProduccionService(repository, productoRepository, presentacionRepository, usuarioRepository,
                inventarioService, inventarioLecheService, recepcionPlantaRepository, consumoRepository,
                detalleRepository, insumoRepository, loteInsumoRepository, recetaRepository,
                new InventarioInsumoService(insumoRepository, movimientoInsumoRepository, usuarioRepository, loteServiceDePrueba(), org.mockito.Mockito.mock(com.planta.acopio.repository.LoteInsumoRepository.class)));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("produccion@planta.com", null));
        Usuario usuario = new Usuario();
        usuario.setId(UUID.randomUUID());
        when(usuarioRepository.findByEmail("produccion@planta.com")).thenReturn(Optional.of(usuario));

        andino = new Producto();
        andino.setId(UUID.randomUUID());
        andino.setNombre("Queso Andino");
        andino.setActivo(true);
        pieza = new Presentacion();
        pieza.setId(UUID.randomUUID());
        pieza.setNombre("Pieza");
        pieza.setUnidadConteo("pieza");
        pieza.setProducto(andino);
        pieza.setActivo(true);
        when(presentacionRepository.findById(pieza.getId())).thenReturn(Optional.of(pieza));

        cuajo = insumo("Cuajo", "g", "15");
        sal = insumo("Sal", "kg", "20");
        receta = new Receta();
        receta.setId(UUID.randomUUID());
        receta.setNombre("Receta estándar");
        receta.setProducto(andino);
        receta.setPresentacion(pieza);
        receta.setLitrosBase(new BigDecimal("100"));
        receta.setRendimientoBase(BigDecimal.ONE);
        receta.getInsumos().add(fila(cuajo));
        receta.getInsumos().add(fila(sal));
        when(recetaRepository.findById(receta.getId())).thenReturn(Optional.of(receta));

        recepcion = new RecepcionPlanta();
        recepcion.setId(UUID.randomUUID());
        recepcion.setFecha(LocalDate.now());
        recepcion.setCreadoEn(LocalDateTime.now());
        recepcion.setLitrosPlanta(new BigDecimal("1500"));

        stubPersistenciaEnMemoria();
    }

    private Insumo insumo(String nombre, String unidad, String stock) {
        Insumo i = new Insumo();
        i.setId(UUID.randomUUID());
        i.setNombre(nombre);
        i.setUnidadMedida(unidad);
        i.setStock(new BigDecimal(stock));
        i.setActivo(true);
        return i;
    }

    private RecetaInsumo fila(Insumo insumo) {
        RecetaInsumo ri = new RecetaInsumo();
        ri.setReceta(receta);
        ri.setInsumo(insumo);
        ri.setCantidad(BigDecimal.ONE);
        return ri;
    }

    /** Lotes, insumos del plan, detalles, stock de insumos y leche como estado en memoria. */
    private void stubPersistenciaEnMemoria() {
        when(repository.findByUuidCliente(any())).thenAnswer(inv -> lotes.values().stream()
                .filter(l -> l.getUuidCliente().equals(inv.getArgument(0))).findFirst());
        when(repository.saveAndFlush(any())).thenAnswer(inv -> {
            LoteProduccion l = inv.getArgument(0);
            if (l.getId() == null) l.setId(UUID.randomUUID());
            lotes.put(l.getId(), l);
            return l;
        });
        when(repository.findById(any())).thenAnswer(inv -> Optional.ofNullable(lotes.get((UUID) inv.getArgument(0))));
        when(repository.bloquearPorId(any())).thenAnswer(inv -> Optional.ofNullable(lotes.get((UUID) inv.getArgument(0))));

        when(loteInsumoRepository.save(any())).thenAnswer(inv -> {
            LoteProduccionInsumo f = inv.getArgument(0);
            insumosPorLote.computeIfAbsent(f.getLote().getId(), k -> new ArrayList<>()).add(f);
            return f;
        });
        when(loteInsumoRepository.findByLoteId(any())).thenAnswer(inv ->
                insumosPorLote.getOrDefault((UUID) inv.getArgument(0), List.of()));

        when(detalleRepository.save(any())).thenAnswer(inv -> {
            LoteProduccionDetalle d = inv.getArgument(0);
            if (d.getId() == null) d.setId(UUID.randomUUID());
            detallesPorLote.computeIfAbsent(d.getLote().getId(), k -> new ArrayList<>()).add(d);
            return d;
        });
        when(detalleRepository.findByLoteId(any())).thenAnswer(inv ->
                detallesPorLote.getOrDefault((UUID) inv.getArgument(0), List.of()));

        // UPDATE condicionado: solo descuenta si alcanza (mismo contrato que la query real).
        when(insumoRepository.descontarStock(any(), any())).thenAnswer(inv -> {
            UUID id = inv.getArgument(0);
            BigDecimal cantidad = inv.getArgument(1);
            Insumo i = id.equals(cuajo.getId()) ? cuajo : id.equals(sal.getId()) ? sal : null;
            if (i == null || i.getStock().compareTo(cantidad) < 0) return 0;
            i.setStock(i.getStock().subtract(cantidad));
            return 1;
        });

        when(recepcionPlantaRepository.buscarConSaldoDisponibleOrdenadasPorAntiguedad()).thenAnswer(inv -> List.of(recepcion));
        when(recepcionPlantaRepository.bloquearPorIds(any())).thenAnswer(inv -> List.of(recepcion));
        when(consumoRepository.sumarLitrosConsumidosPorRecepcion(any())).thenAnswer(inv ->
                consumidoPorRecepcion.getOrDefault((UUID) inv.getArgument(0), BigDecimal.ZERO));
        when(consumoRepository.save(any())).thenAnswer(inv -> {
            LoteProduccionRecepcionConsumo c = inv.getArgument(0);
            consumidoPorRecepcion.merge(c.getRecepcionPlanta().getId(), c.getLitrosConsumidos(), BigDecimal::add);
            return c;
        });
        when(consumoRepository.findByLoteProduccionId(any())).thenReturn(List.of());
        when(inventarioLecheService.obtenerSaldoDisponibleLitros()).thenAnswer(inv ->
                recepcion.getLitrosPlanta().subtract(consumidoPorRecepcion.getOrDefault(recepcion.getId(), BigDecimal.ZERO)));
    }

    private LoteProduccionResponse planificar(String litros) {
        return service.planificar(new PlanificarLoteRequest(UUID.randomUUID().toString(), LocalDate.now(),
                receta.getId(), new BigDecimal(litros)));
    }

    private FinalizarLoteRequest resultado(int obtenidas) {
        return new FinalizarLoteRequest(obtenidas == 0 ? List.of()
                : List.of(new DetalleSalidaRequest(pieza.getId(), obtenidas)), null);
    }

    // ---- Planificar ----

    @Test
    void planificarCongelaElPlanSinTocarNingunStockNiRegistrarProductoTerminado() {
        LoteProduccionResponse lote = planificar("1000");

        assertThat(lote.estado()).isEqualTo("PLANIFICADA");
        assertThat(lote.cantidadEsperada()).isEqualByComparingTo("10");
        assertThat(lote.litrosUsados()).isEqualByComparingTo("1000");
        assertThat(lote.recetaNombre()).isEqualTo("Receta estándar");
        assertThat(lote.presentacionEsperadaNombre()).isEqualTo("Pieza");
        assertThat(lote.insumos()).extracting(i -> i.cantidad().stripTrailingZeros().toPlainString())
                .containsExactly("10", "10");
        // Esperado != producido: ninguna salida real todavía.
        assertThat(lote.detalles()).isEmpty();
        assertThat(lote.diferencia()).isNull();
        verify(inventarioService, never()).registrarEntradaProduccion(any());
        verify(inventarioLecheService, never()).registrarSalidaProduccion(any(), any());
        verify(insumoRepository, never()).descontarStock(any(), any());
        verify(recepcionPlantaRepository, never()).bloquearPorIds(any());
    }

    @Test
    void planificarEsIdempotentePorUuidCliente() {
        var dto = new PlanificarLoteRequest("uuid-1", LocalDate.now(), receta.getId(), new BigDecimal("1000"));
        LoteProduccionResponse primero = service.planificar(dto);
        LoteProduccionResponse reintento = service.planificar(dto);

        assertThat(reintento.id()).isEqualTo(primero.id());
        assertThat(lotes).hasSize(1);
        assertThatThrownBy(() -> service.planificar(new PlanificarLoteRequest("uuid-1", LocalDate.now(), receta.getId(), new BigDecimal("500"))))
                .isInstanceOf(ConflictoNegocioException.class);
    }

    @Test
    void planificarRechazaRecetaDesactivada() {
        receta.setActivo(false);
        assertThatThrownBy(() -> planificar("1000")).isInstanceOf(RecursoInactivoException.class);
    }

    @Test
    void planificarPermiteAunqueFalteStockPeroIniciarLoBloquea() {
        sal.setStock(new BigDecimal("6"));
        LoteProduccionResponse lote = planificar("1000");
        assertThat(lote.estado()).isEqualTo("PLANIFICADA");

        DisponibilidadProduccionResponse d = service.disponibilidad(lote.id());
        assertThat(d.todoDisponible()).isFalse();
        assertThat(d.insumos().get(1).faltante()).isEqualByComparingTo("4");

        assertThatThrownBy(() -> service.iniciar(lote.id()))
                .isInstanceOf(ValidacionNegocioException.class)
                .hasMessageContaining("faltan 4 kg de Sal");
        assertThat(lotes.get(lote.id()).getEstado()).isEqualTo(EstadoLote.PLANIFICADA);
        verify(insumoRepository, never()).descontarStock(any(), any());
        verify(inventarioLecheService, never()).registrarSalidaProduccion(any(), any());
    }

    // ---- Iniciar ----

    @Test
    void iniciarConsumeLecheEInsumosUnaSolaVez() {
        LoteProduccionResponse lote = planificar("1000");

        LoteProduccionResponse iniciado = service.iniciar(lote.id());

        assertThat(iniciado.estado()).isEqualTo("EN_PRODUCCION");
        assertThat(iniciado.iniciadoEn()).isNotNull();
        assertThat(cuajo.getStock()).isEqualByComparingTo("5");
        assertThat(sal.getStock()).isEqualByComparingTo("10");
        assertThat(consumidoPorRecepcion.get(recepcion.getId())).isEqualByComparingTo("1000");
        verify(inventarioLecheService, times(1)).registrarSalidaProduccion(any(), eq(new BigDecimal("1000")));
        // Aun sin producto terminado: se registra solo al finalizar.
        verify(inventarioService, never()).registrarEntradaProduccion(any());

        // V19: cada insumo consumido deja su SALIDA_PRODUCCION (negativa, ligada al lote) en el kardex.
        verify(movimientoInsumoRepository, times(2)).save(org.mockito.ArgumentMatchers.argThat(m ->
                m.getTipoMovimiento() == com.planta.acopio.entity.TipoMovimientoInsumo.SALIDA_PRODUCCION
                        && m.getLoteProduccion() != null && m.getLoteProduccion().getId().equals(lote.id())
                        && m.getCantidad().compareTo(new BigDecimal("-10")) == 0));

        // Reintento (doble click / red): devuelve el lote sin volver a consumir.
        service.iniciar(lote.id());
        assertThat(cuajo.getStock()).isEqualByComparingTo("5");
        verify(inventarioLecheService, times(1)).registrarSalidaProduccion(any(), any());
        verify(insumoRepository, times(2)).descontarStock(any(), any());
        verify(movimientoInsumoRepository, times(2)).save(any());
    }

    @Test
    void iniciarRechazaSiFaltaLecheInformandoSolicitadoDisponibleYFaltante() {
        recepcion.setLitrosPlanta(new BigDecimal("650"));
        LoteProduccionResponse lote = planificar("1000");

        assertThatThrownBy(() -> service.iniciar(lote.id()))
                .isInstanceOf(ValidacionNegocioException.class)
                .hasMessageContaining("solicitado 1000 L, disponible 650 L, faltan 350 L");
        assertThat(cuajo.getStock()).isEqualByComparingTo("15");
        assertThat(lotes.get(lote.id()).getEstado()).isEqualTo(EstadoLote.PLANIFICADA);
    }

    @Test
    void iniciarRechazaSiFaltaCuajo() {
        cuajo.setStock(new BigDecimal("9.99"));
        LoteProduccionResponse lote = planificar("1000");

        assertThatThrownBy(() -> service.iniciar(lote.id()))
                .isInstanceOf(ValidacionNegocioException.class)
                .hasMessageContaining("de Cuajo");
    }

    @Test
    void iniciarConStockExactamenteIgualAlNecesarioFuncionaYDejaStockEnCero() {
        cuajo.setStock(new BigDecimal("10"));
        sal.setStock(new BigDecimal("10"));
        recepcion.setLitrosPlanta(new BigDecimal("1000"));
        LoteProduccionResponse lote = planificar("1000");

        service.iniciar(lote.id());

        assertThat(cuajo.getStock()).isEqualByComparingTo("0");
        assertThat(sal.getStock()).isEqualByComparingTo("0");
    }

    @Test
    void dosProduccionesNoPuedenConsumirElMismoStock() {
        LoteProduccionResponse a = planificar("1000");
        LoteProduccionResponse b = planificar("1000"); // entre las dos piden 20 g de cuajo; hay 15

        service.iniciar(a.id());
        assertThatThrownBy(() -> service.iniciar(b.id())).isInstanceOf(ValidacionNegocioException.class);

        assertThat(cuajo.getStock()).isEqualByComparingTo("5");      // nunca negativo
        assertThat(lotes.get(b.id()).getEstado()).isEqualTo(EstadoLote.PLANIFICADA);
    }

    @Test
    void siElStockCambiaEntreLaValidacionYElDescuentoSeRechaza() {
        LoteProduccionResponse lote = planificar("1000");
        // Otra transacción deja la sal en 3 justo antes del UPDATE condicionado.
        doReturn(0).when(insumoRepository).descontarStock(eq(sal.getId()), any());

        assertThatThrownBy(() -> service.iniciar(lote.id()))
                .isInstanceOf(ValidacionNegocioException.class)
                .hasMessageContaining("Stock insuficiente de Sal");
    }

    @Test
    void iniciarRechazaUnInsumoDesactivadoDespuesDePlanificar() {
        LoteProduccionResponse lote = planificar("1000");
        sal.setActivo(false);

        assertThatThrownBy(() -> service.iniciar(lote.id())).isInstanceOf(RecursoInactivoException.class);
    }

    // ---- Finalizar ----

    @Test
    void finalizarRegistraElResultadoRealDistintoDelEsperadoYRecienAhiIngresaInventario() {
        LoteProduccionResponse lote = planificar("1000");
        service.iniciar(lote.id());

        LoteProduccionResponse fin = service.finalizar(lote.id(), resultado(9));

        assertThat(fin.estado()).isEqualTo("FINALIZADA");
        assertThat(fin.finalizadoEn()).isNotNull();
        assertThat(fin.cantidadEsperada()).isEqualByComparingTo("10");
        assertThat(fin.detalles()).singleElement().satisfies(d -> assertThat(d.cantidadObtenida()).isEqualTo(9));
        assertThat(fin.diferencia()).isEqualByComparingTo("-1");
        assertThat(fin.litrosUsados()).isEqualByComparingTo("1000");
        verify(inventarioService, times(1)).registrarEntradaProduccion(any());
    }

    @Test
    void noSePuedeFinalizarUnLoteQueNoEstaEnProduccion() {
        LoteProduccionResponse lote = planificar("1000");

        assertThatThrownBy(() -> service.finalizar(lote.id(), resultado(9)))
                .isInstanceOf(ConflictoNegocioException.class)
                .hasMessageContaining("PLANIFICADA");
        verify(inventarioService, never()).registrarEntradaProduccion(any());
    }

    @Test
    void finalizarDosVecesNoDuplicaInventario() {
        LoteProduccionResponse lote = planificar("1000");
        service.iniciar(lote.id());
        service.finalizar(lote.id(), resultado(9));

        // Reintento idéntico: devuelve el lote sin volver a ingresar inventario.
        LoteProduccionResponse reintento = service.finalizar(lote.id(), resultado(9));
        assertThat(reintento.estado()).isEqualTo("FINALIZADA");
        verify(inventarioService, times(1)).registrarEntradaProduccion(any());

        // Otro resultado: conflicto, el resultado ya registrado no se modifica.
        assertThatThrownBy(() -> service.finalizar(lote.id(), resultado(10)))
                .isInstanceOf(ConflictoNegocioException.class);
        assertThat(detallesPorLote.get(lote.id())).singleElement()
                .satisfies(d -> assertThat(d.getCantidadObtenida()).isEqualTo(9));
    }

    @Test
    void finalizarSinSalidasRepresentaUnLotePerdido() {
        LoteProduccionResponse lote = planificar("1000");
        service.iniciar(lote.id());

        LoteProduccionResponse fin = service.finalizar(lote.id(), resultado(0));

        assertThat(fin.detalles()).isEmpty();
        assertThat(fin.diferencia()).isEqualByComparingTo("-10");
        verify(inventarioService, never()).registrarEntradaProduccion(any());
    }

    // ---- Cancelar ----

    @Test
    void cancelarUnLotePlanificadoNoConsumeNada() {
        LoteProduccionResponse lote = planificar("1000");

        LoteProduccionResponse cancelado = service.cancelar(lote.id());

        assertThat(cancelado.estado()).isEqualTo("CANCELADA");
        assertThat(cancelado.canceladoEn()).isNotNull();
        assertThat(cuajo.getStock()).isEqualByComparingTo("15");
        verify(insumoRepository, never()).descontarStock(any(), any());
        verify(movimientoInsumoRepository, never()).save(any());
        assertThatThrownBy(() -> service.iniciar(lote.id())).isInstanceOf(ConflictoNegocioException.class);
    }

    @Test
    void noSeCancelaUnLoteEnProduccion() {
        LoteProduccionResponse lote = planificar("1000");
        service.iniciar(lote.id());

        assertThatThrownBy(() -> service.cancelar(lote.id()))
                .isInstanceOf(ConflictoNegocioException.class)
                .hasMessageContaining("ya se consumieron");
        assertThat(lotes.get(lote.id()).getEstado()).isEqualTo(EstadoLote.EN_PRODUCCION);
    }

    @Test
    void noSeCancelaUnLoteFinalizado() {
        LoteProduccionResponse lote = planificar("100");
        service.iniciar(lote.id());
        service.finalizar(lote.id(), resultado(1));

        assertThatThrownBy(() -> service.cancelar(lote.id())).isInstanceOf(ConflictoNegocioException.class);
    }

    // ---- Fase 1: control de vencimiento al finalizar ----

    private FinalizarLoteRequest resultadoConVencimiento(int obtenidas, LocalDate vencimiento) {
        return new FinalizarLoteRequest(List.of(new DetalleSalidaRequest(pieza.getId(), obtenidas)), null,
                vencimiento == null ? List.of() : List.of(new VencimientoSalidaRequest(pieza.getId(), vencimiento)));
    }

    @Test
    void finalizarUnProductoControladoRegistraLaFechaYLaEntradaTrazada() {
        when(inventarioService.controlaVencimiento(pieza.getId())).thenReturn(true);
        LoteProduccionResponse lote = planificar("1000");
        service.iniciar(lote.id());
        LocalDate vence = LocalDate.now().plusDays(20);

        service.finalizar(lote.id(), resultadoConVencimiento(9, vence));

        verify(inventarioService).registrarEntradaProduccionTrazada(any());
        verify(inventarioService, never()).registrarEntradaProduccion(any());
        assertThat(detallesPorLote.get(lote.id())).singleElement()
                .satisfies(d -> assertThat(d.getFechaVencimiento()).isEqualTo(vence));
    }

    @Test
    void finalizarBloqueaLasPresentacionesAntesDeEscribirNada() {
        when(inventarioService.controlaVencimiento(pieza.getId())).thenReturn(true);
        LoteProduccionResponse lote = planificar("1000");
        service.iniciar(lote.id());

        service.finalizar(lote.id(), resultadoConVencimiento(9, LocalDate.now().plusDays(5)));

        var orden = org.mockito.Mockito.inOrder(inventarioService, detalleRepository);
        orden.verify(inventarioService).bloquearPresentaciones(List.of(pieza.getId()));
        orden.verify(detalleRepository).save(any());
    }

    @Test
    void finalizarUnProductoControladoSinFechaSeRechazaSinEscribirNada() {
        when(inventarioService.controlaVencimiento(pieza.getId())).thenReturn(true);
        LoteProduccionResponse lote = planificar("1000");
        service.iniciar(lote.id());

        assertThatThrownBy(() -> service.finalizar(lote.id(), resultado(9)))
                .isInstanceOf(ValidacionNegocioException.class)
                .hasMessageContaining("Falta la fecha de vencimiento");

        verify(detalleRepository, never()).save(any());
        verify(inventarioService, never()).registrarEntradaProduccionTrazada(any());
        verify(inventarioService, never()).registrarEntradaProduccion(any());
    }

    @Test
    void finalizarRechazaUnVencimientoAnteriorALaFechaDelLote() {
        when(inventarioService.controlaVencimiento(pieza.getId())).thenReturn(true);
        LoteProduccionResponse lote = planificar("1000");
        service.iniciar(lote.id());

        assertThatThrownBy(() -> service.finalizar(lote.id(), resultadoConVencimiento(9, LocalDate.now().minusDays(1))))
                .isInstanceOf(ValidacionNegocioException.class)
                .hasMessageContaining("anterior a la fecha del lote");

        verify(detalleRepository, never()).save(any());
    }

    @Test
    void finalizarRechazaFechasRepetidasOParaUnaPresentacionQueNoEsSalida() {
        when(inventarioService.controlaVencimiento(pieza.getId())).thenReturn(true);
        LoteProduccionResponse lote = planificar("1000");
        service.iniciar(lote.id());
        LocalDate vence = LocalDate.now().plusDays(5);

        assertThatThrownBy(() -> service.finalizar(lote.id(), new FinalizarLoteRequest(
                List.of(new DetalleSalidaRequest(pieza.getId(), 9)), null,
                List.of(new VencimientoSalidaRequest(pieza.getId(), vence), new VencimientoSalidaRequest(pieza.getId(), vence)))))
                .isInstanceOf(ValidacionNegocioException.class).hasMessageContaining("repetida");

        assertThatThrownBy(() -> service.finalizar(lote.id(), new FinalizarLoteRequest(
                List.of(new DetalleSalidaRequest(pieza.getId(), 9)), null,
                List.of(new VencimientoSalidaRequest(pieza.getId(), vence), new VencimientoSalidaRequest(UUID.randomUUID(), vence)))))
                .isInstanceOf(ValidacionNegocioException.class).hasMessageContaining("no es una salida");

        verify(detalleRepository, never()).save(any());
    }

    @Test
    void finalizarUnProductoSinControlRechazaFechasYSigueIgualQueAntes() {
        LoteProduccionResponse lote = planificar("1000");
        service.iniciar(lote.id());

        assertThatThrownBy(() -> service.finalizar(lote.id(), resultadoConVencimiento(9, LocalDate.now().plusDays(5))))
                .isInstanceOf(ValidacionNegocioException.class)
                .hasMessageContaining("no controla vencimiento");
        verify(detalleRepository, never()).save(any());

        // Sin fechas: exactamente el comportamiento de siempre.
        service.finalizar(lote.id(), resultado(9));
        verify(inventarioService).registrarEntradaProduccion(any());
        verify(inventarioService, never()).registrarEntradaProduccionTrazada(any());
    }

    /** V43: el consumo de insumos sale de lotes; estas pruebas verifican produccion, no el reparto, asi que cada insumo tiene un lote que lo cubre. */
    private static LoteInsumoService loteServiceDePrueba() {
        LoteInsumoService s = org.mockito.Mockito.mock(LoteInsumoService.class);
        org.mockito.Mockito.lenient().when(s.asignar(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> {
                    com.planta.acopio.entity.LoteInsumo l = new com.planta.acopio.entity.LoteInsumo();
                    l.setId(java.util.UUID.randomUUID());
                    l.setInsumo(inv.getArgument(0));
                    l.setCodigo("L-1");
                    return java.util.List.of(new LoteInsumoService.Asignacion(l, inv.getArgument(1)));
                });
        org.mockito.Mockito.lenient().when(s.stockConsumible(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> ((com.planta.acopio.entity.Insumo) inv.getArgument(0)).getStock());
        return s;
    }
}
