package com.planta.acopio.service;

import com.planta.acopio.dto.CrearLoteRequest;
import com.planta.acopio.dto.DetalleSalidaRequest;
import com.planta.acopio.dto.InsumoUsadoRequest;
import com.planta.acopio.entity.Insumo;
import com.planta.acopio.entity.LoteProduccion;
import com.planta.acopio.entity.LoteProduccionDetalle;
import com.planta.acopio.entity.LoteProduccionInsumo;
import com.planta.acopio.entity.LoteProduccionRecepcionConsumo;
import com.planta.acopio.entity.Presentacion;
import com.planta.acopio.entity.RecepcionPlanta;
import com.planta.acopio.entity.Producto;
import com.planta.acopio.entity.Usuario;
import com.planta.acopio.exception.ConflictoNegocioException;
import com.planta.acopio.exception.RecursoInactivoException;
import com.planta.acopio.exception.RecursoNoEncontradoException;
import com.planta.acopio.exception.ValidacionNegocioException;
import com.planta.acopio.repository.InsumoRepository;
import com.planta.acopio.repository.LoteProduccionDetalleRepository;
import com.planta.acopio.repository.LoteProduccionInsumoRepository;
import com.planta.acopio.repository.LoteProduccionRecepcionConsumoRepository;
import com.planta.acopio.repository.LoteProduccionRepository;
import com.planta.acopio.repository.PresentacionRepository;
import com.planta.acopio.repository.RecepcionPlantaRepository;
import com.planta.acopio.repository.ProductoRepository;
import com.planta.acopio.repository.RecetaRepository;
import com.planta.acopio.repository.MovimientoInsumoRepository;
import com.planta.acopio.repository.UsuarioRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

// Refactor de Produccion, fase 2 (nuevo modelo funcional): ACOPIO -> RECEPCION -> EXISTENCIA DE LECHE ->
// PRODUCCION -> EXISTENCIA DE PRODUCTO TERMINADO (consumo de leche por FIFO, sin cambios en esta fase).
//
// Fase 3 (Produccion con varias presentaciones de salida e Inventario por presentacion): un lote consume
// leche UNA sola vez pero puede generar VARIAS presentaciones de salida del mismo producto -- CrearLoteRequest
// reemplaza el antiguo `unidadesObtenidas` unico por una lista `detalles` (presentacion + cantidad).
@ExtendWith(MockitoExtension.class)
class LoteProduccionServiceTest {

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
    private final UUID productoId = UUID.randomUUID();
    private final UUID presentacionId = UUID.randomUUID();
    private final Pageable pageable = PageRequest.of(0, 20);

    @BeforeEach
    void setUp() {
        service = new LoteProduccionService(repository, productoRepository, presentacionRepository, usuarioRepository,
                inventarioService, inventarioLecheService, recepcionPlantaRepository, consumoRepository, detalleRepository,
                insumoRepository, loteInsumoRepository, recetaRepository,
                new InventarioInsumoService(insumoRepository, movimientoInsumoRepository, usuarioRepository, loteServiceDePrueba(), org.mockito.Mockito.mock(com.planta.acopio.repository.LoteInsumoRepository.class)));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("produccion@planta.com", null));
    }

    private Producto productoConId() {
        Producto t = new Producto();
        t.setId(productoId);
        t.setNombre("Queso Fresco");
        t.setRendimientoEsperadoPct(BigDecimal.valueOf(12));
        t.setActivo(true);
        return t;
    }

    /** Sin contenido declarado (shape "Unidad"/"Pieza"): la que habilita la formula historica de rendimiento. */
    private Presentacion presentacionUnidad(UUID id, Producto producto) {
        Presentacion p = new Presentacion();
        p.setId(id);
        p.setNombre("Unidad");
        p.setProducto(producto);
        p.setActivo(true);
        return p;
    }

    /** Con contenido declarado (ej. "Botella 1 L"): nunca entra en la formula antigua de rendimiento. */
    private Presentacion presentacionConContenido(UUID id, String nombre, Producto producto) {
        Presentacion p = new Presentacion();
        p.setId(id);
        p.setNombre(nombre);
        p.setProducto(producto);
        p.setContenidoCantidad(BigDecimal.ONE);
        p.setActivo(true);
        return p;
    }

    private RecepcionPlanta recepcion(BigDecimal litrosPlanta, LocalDate fecha, LocalDateTime creadoEn) {
        RecepcionPlanta r = new RecepcionPlanta();
        r.setId(UUID.randomUUID());
        r.setFecha(fecha);
        r.setLitrosPlanta(litrosPlanta);
        r.setCreadoEn(creadoEn);
        return r;
    }

    private void stubRecepciones(List<RecepcionPlanta> recepciones) {
        when(recepcionPlantaRepository.buscarConSaldoDisponibleOrdenadasPorAntiguedad()).thenReturn(recepciones);
        when(recepcionPlantaRepository.bloquearPorIds(any())).thenAnswer(inv -> {
            List<UUID> ids = inv.getArgument(0);
            return recepciones.stream().filter(r -> ids.contains(r.getId())).toList();
        });
    }

    private Map<UUID, BigDecimal> stubLedgerDeConsumoDinamico() {
        Map<UUID, BigDecimal> consumido = new HashMap<>();
        when(consumoRepository.sumarLitrosConsumidosPorRecepcion(any())).thenAnswer(inv ->
                consumido.getOrDefault((UUID) inv.getArgument(0), BigDecimal.ZERO));
        when(consumoRepository.save(any())).thenAnswer(inv -> {
            LoteProduccionRecepcionConsumo c = inv.getArgument(0);
            consumido.merge(c.getRecepcionPlanta().getId(), c.getLitrosConsumidos(), BigDecimal::add);
            return c;
        });
        return consumido;
    }

    private void stubGuardarLoteConId() {
        when(repository.saveAndFlush(any())).thenAnswer(inv -> {
            LoteProduccion l = inv.getArgument(0);
            if (l.getId() == null) l.setId(UUID.randomUUID());
            return l;
        });
    }

    /** Libro mayor de detalles en memoria: detalleRepository.save() alimenta lo que findByLoteId() lee
     *  después -- simula el efecto real de persistir y volver a leer dentro de la misma transacción (mismo
     *  patrón que stubLedgerDeConsumoDinamico). Sin esto, toResponse() vería siempre una lista vacía. */
    private void stubGuardarDetalleConId() {
        Map<UUID, List<LoteProduccionDetalle>> guardados = new HashMap<>();
        lenient().when(detalleRepository.save(any())).thenAnswer(inv -> {
            LoteProduccionDetalle d = inv.getArgument(0);
            if (d.getId() == null) d.setId(UUID.randomUUID());
            guardados.computeIfAbsent(d.getLote().getId(), k -> new ArrayList<>()).add(d);
            return d;
        });
        lenient().when(detalleRepository.findByLoteId(any())).thenAnswer(inv ->
                guardados.getOrDefault((UUID) inv.getArgument(0), List.of()));
    }

    private void stubUsuarioAutenticado() {
        Usuario responsable = new Usuario();
        responsable.setId(UUID.randomUUID());
        when(usuarioRepository.findByEmail("produccion@planta.com")).thenReturn(Optional.of(responsable));
    }

    private void stubSinTrazabilidadPrevia() {
        lenient().when(consumoRepository.findByLoteProduccionId(any())).thenReturn(List.of());
    }

    // ---- Consumo FIFO de leche: sin cambios de comportamiento en esta fase ----

    @Test
    void variasProduccionesSobreLaMismaRecepcionConsumenPorFifoYRechazanCuandoNoAlcanza() {
        RecepcionPlanta r1 = recepcion(BigDecimal.valueOf(300), LocalDate.now(), LocalDateTime.now());
        stubRecepciones(List.of(r1));
        Map<UUID, BigDecimal> consumido = stubLedgerDeConsumoDinamico();
        stubUsuarioAutenticado();
        stubGuardarLoteConId();
        stubGuardarDetalleConId();
        stubSinTrazabilidadPrevia();

        Producto quesoFresco = productoConId();
        when(productoRepository.findById(productoId)).thenReturn(Optional.of(quesoFresco));
        when(presentacionRepository.findById(presentacionId)).thenReturn(Optional.of(presentacionUnidad(presentacionId, quesoFresco)));

        UUID productoAndinoId = UUID.randomUUID();
        UUID presentacionAndinoId = UUID.randomUUID();
        Producto andino = new Producto();
        andino.setId(productoAndinoId);
        andino.setNombre("Queso Andino");
        andino.setRendimientoEsperadoPct(BigDecimal.valueOf(10));
        andino.setActivo(true);
        when(productoRepository.findById(productoAndinoId)).thenReturn(Optional.of(andino));
        when(presentacionRepository.findById(presentacionAndinoId)).thenReturn(Optional.of(presentacionUnidad(presentacionAndinoId, andino)));

        var lote1 = service.crear(new CrearLoteRequest(UUID.randomUUID().toString(), LocalDate.now(), productoId,
                BigDecimal.valueOf(150), List.of(new DetalleSalidaRequest(presentacionId, 20))));
        assertThat(consumido.get(r1.getId())).isEqualByComparingTo(BigDecimal.valueOf(150));

        var lote2 = service.crear(new CrearLoteRequest(UUID.randomUUID().toString(), LocalDate.now(), productoAndinoId,
                BigDecimal.valueOf(100), List.of(new DetalleSalidaRequest(presentacionAndinoId, 15))));
        assertThat(consumido.get(r1.getId())).isEqualByComparingTo(BigDecimal.valueOf(250));
        assertThat(lote1.productoNombre()).isEqualTo("Queso Fresco");
        assertThat(lote2.productoNombre()).isEqualTo("Queso Andino");

        var dto3 = new CrearLoteRequest(UUID.randomUUID().toString(), LocalDate.now(), productoId,
                BigDecimal.valueOf(60), List.of(new DetalleSalidaRequest(presentacionId, 5)));
        assertThatThrownBy(() -> service.crear(dto3))
                .isInstanceOf(ValidacionNegocioException.class)
                .hasMessageContaining("Leche disponible insuficiente");
        assertThat(consumido.get(r1.getId())).isEqualByComparingTo(BigDecimal.valueOf(250));
        verify(repository, times(2)).saveAndFlush(any());
        verify(inventarioLecheService, times(2)).registrarSalidaProduccion(any(), any());
    }

    @Test
    void rechazaSiNoHayNingunaRecepcionConSaldoDisponible() {
        String uuidCliente = UUID.randomUUID().toString();
        when(repository.findByUuidCliente(uuidCliente)).thenReturn(Optional.empty());
        Producto producto = productoConId();
        when(productoRepository.findById(productoId)).thenReturn(Optional.of(producto));
        when(presentacionRepository.findById(presentacionId)).thenReturn(Optional.of(presentacionUnidad(presentacionId, producto)));
        when(recepcionPlantaRepository.buscarConSaldoDisponibleOrdenadasPorAntiguedad()).thenReturn(List.of());

        var dto = new CrearLoteRequest(uuidCliente, LocalDate.now(), productoId, BigDecimal.valueOf(50),
                List.of(new DetalleSalidaRequest(presentacionId, 5)));

        assertThatThrownBy(() -> service.crear(dto))
                .isInstanceOf(ValidacionNegocioException.class)
                .hasMessageContaining("Leche disponible insuficiente");
        verify(recepcionPlantaRepository, never()).bloquearPorIds(any());
        verify(repository, never()).saveAndFlush(any());
    }

    // ---- Fase 3: varias presentaciones de salida en un mismo lote ----

    @Test
    void unLoteConVariasPresentacionesConsumeLaLecheUnaSolaVezYGeneraUnDetalleYMovimientoPorPresentacion() {
        Producto yogurt = productoConId();
        yogurt.setNombre("Yogurt de fresa");
        yogurt.setRendimientoEsperadoPct(null); // familia sin regla de rendimiento (no es queso)
        UUID pres2L = UUID.randomUUID();
        UUID pres1L = UUID.randomUUID();
        UUID pres500 = UUID.randomUUID();
        when(productoRepository.findById(productoId)).thenReturn(Optional.of(yogurt));
        when(presentacionRepository.findById(pres2L)).thenReturn(Optional.of(presentacionConContenido(pres2L, "Botella 2 L", yogurt)));
        when(presentacionRepository.findById(pres1L)).thenReturn(Optional.of(presentacionConContenido(pres1L, "Botella 1 L", yogurt)));
        when(presentacionRepository.findById(pres500)).thenReturn(Optional.of(presentacionConContenido(pres500, "Botella 500 ml", yogurt)));
        RecepcionPlanta r1 = recepcion(BigDecimal.valueOf(100), LocalDate.now(), LocalDateTime.now());
        stubRecepciones(List.of(r1));
        stubLedgerDeConsumoDinamico();
        stubUsuarioAutenticado();
        stubGuardarLoteConId();
        stubGuardarDetalleConId();
        stubSinTrazabilidadPrevia();

        var dto = new CrearLoteRequest(UUID.randomUUID().toString(), LocalDate.now(), productoId, BigDecimal.valueOf(100),
                List.of(new DetalleSalidaRequest(pres2L, 20), new DetalleSalidaRequest(pres1L, 40), new DetalleSalidaRequest(pres500, 30)));

        service.crear(dto);

        // Leche consumida UNA sola vez por el total, no tres veces.
        verify(inventarioLecheService, times(1)).registrarSalidaProduccion(any(), eq(BigDecimal.valueOf(100)));
        verify(consumoRepository, times(1)).save(any());

        // Un detalle y un movimiento de inventario por presentacion, nunca uno por lote.
        ArgumentCaptor<LoteProduccionDetalle> detalleCaptor = ArgumentCaptor.forClass(LoteProduccionDetalle.class);
        verify(detalleRepository, times(3)).save(detalleCaptor.capture());
        assertThat(detalleCaptor.getAllValues()).extracting(LoteProduccionDetalle::getCantidadObtenida)
                .containsExactlyInAnyOrder(20, 40, 30);
        verify(inventarioService, times(3)).registrarEntradaProduccion(any());
    }

    // ---- Insumos usados por lote (V16): opcionales, validados antes de tocar la leche ----

    private Insumo insumo(String nombre, boolean activo) {
        Insumo i = new Insumo();
        i.setId(UUID.randomUUID());
        i.setNombre(nombre);
        i.setUnidadMedida("unidades");
        i.setActivo(activo);
        i.setStock(new BigDecimal("100"));
        return i;
    }

    @Test
    void registraLosInsumosUsadosEnElLote() {
        Producto producto = productoConId();
        when(productoRepository.findById(productoId)).thenReturn(Optional.of(producto));
        when(presentacionRepository.findById(presentacionId)).thenReturn(Optional.of(presentacionUnidad(presentacionId, producto)));
        Insumo cuajo = insumo("Cuajo", true);
        Insumo sal = insumo("Sal", true);
        when(insumoRepository.findById(cuajo.getId())).thenReturn(Optional.of(cuajo));
        when(insumoRepository.findById(sal.getId())).thenReturn(Optional.of(sal));
        when(insumoRepository.descontarStock(any(), any())).thenReturn(1);
        stubRecepciones(List.of(recepcion(BigDecimal.valueOf(100), LocalDate.now(), LocalDateTime.now())));
        stubLedgerDeConsumoDinamico();
        stubUsuarioAutenticado();
        stubGuardarLoteConId();
        stubGuardarDetalleConId();
        stubSinTrazabilidadPrevia();

        var dto = new CrearLoteRequest(UUID.randomUUID().toString(), LocalDate.now(), productoId, BigDecimal.valueOf(100),
                List.of(new DetalleSalidaRequest(presentacionId, 12)), null,
                List.of(new InsumoUsadoRequest(cuajo.getId(), new BigDecimal("2")),
                        new InsumoUsadoRequest(sal.getId(), new BigDecimal("0.5"))));

        service.crear(dto);

        ArgumentCaptor<LoteProduccionInsumo> captor = ArgumentCaptor.forClass(LoteProduccionInsumo.class);
        verify(loteInsumoRepository, times(2)).save(captor.capture());
        assertThat(captor.getAllValues()).extracting(i -> i.getInsumo().getNombre()).containsExactly("Cuajo", "Sal");
        verify(inventarioService, times(1)).registrarEntradaProduccion(any());
        // V18: los insumos registrados en el lote se descuentan de su stock, una vez cada uno.
        verify(insumoRepository).descontarStock(cuajo.getId(), new BigDecimal("2"));
        verify(insumoRepository).descontarStock(sal.getId(), new BigDecimal("0.5"));
    }

    @Test
    void registroDirectoRechazaInsumoSinStockSuficienteSinTocarLaLeche() {
        Producto producto = productoConId();
        when(productoRepository.findById(productoId)).thenReturn(Optional.of(producto));
        when(presentacionRepository.findById(presentacionId)).thenReturn(Optional.of(presentacionUnidad(presentacionId, producto)));
        Insumo sal = insumo("Sal", true);
        sal.setStock(new BigDecimal("6"));
        sal.setUnidadMedida("kg");
        when(insumoRepository.findById(sal.getId())).thenReturn(Optional.of(sal));

        var dto = new CrearLoteRequest(UUID.randomUUID().toString(), LocalDate.now(), productoId, BigDecimal.valueOf(100),
                List.of(new DetalleSalidaRequest(presentacionId, 12)), null,
                List.of(new InsumoUsadoRequest(sal.getId(), new BigDecimal("10"))));

        assertThatThrownBy(() -> service.crear(dto))
                .isInstanceOf(ValidacionNegocioException.class)
                .hasMessageContaining("faltan 4 kg de Sal");
        verify(recepcionPlantaRepository, never()).buscarConSaldoDisponibleOrdenadasPorAntiguedad();
        verify(repository, never()).saveAndFlush(any());
        verify(insumoRepository, never()).descontarStock(any(), any());
    }

    @Test
    void registroDirectoRevierteSiElStockDelInsumoCambioConcurrentemente() {
        Producto producto = productoConId();
        when(productoRepository.findById(productoId)).thenReturn(Optional.of(producto));
        when(presentacionRepository.findById(presentacionId)).thenReturn(Optional.of(presentacionUnidad(presentacionId, producto)));
        Insumo cuajo = insumo("Cuajo", true);
        when(insumoRepository.findById(cuajo.getId())).thenReturn(Optional.of(cuajo));
        // La lectura previa decía que alcanzaba; el UPDATE condicionado ya no encuentra stock suficiente.
        when(insumoRepository.descontarStock(any(), any())).thenReturn(0);
        stubRecepciones(List.of(recepcion(BigDecimal.valueOf(100), LocalDate.now(), LocalDateTime.now())));
        stubLedgerDeConsumoDinamico();
        stubUsuarioAutenticado();
        stubGuardarLoteConId();
        stubSinTrazabilidadPrevia();

        var dto = new CrearLoteRequest(UUID.randomUUID().toString(), LocalDate.now(), productoId, BigDecimal.valueOf(100),
                List.of(new DetalleSalidaRequest(presentacionId, 12)), null,
                List.of(new InsumoUsadoRequest(cuajo.getId(), new BigDecimal("2"))));

        // La excepción marca la transacción para rollback: leche/lote escritos antes se revierten juntos
        // (verificado contra Postgres real en RecetaProduccionIntegrationTest).
        assertThatThrownBy(() -> service.crear(dto))
                .isInstanceOf(ValidacionNegocioException.class)
                .hasMessageContaining("Stock insuficiente de Cuajo");
        verify(inventarioService, never()).registrarEntradaProduccion(any());
    }

    @Test
    void rechazaUnInsumoDesactivadoSinTocarLaLeche() {
        Producto producto = productoConId();
        when(productoRepository.findById(productoId)).thenReturn(Optional.of(producto));
        when(presentacionRepository.findById(presentacionId)).thenReturn(Optional.of(presentacionUnidad(presentacionId, producto)));
        Insumo inactivo = insumo("Cuajo", false);
        when(insumoRepository.findById(inactivo.getId())).thenReturn(Optional.of(inactivo));

        var dto = new CrearLoteRequest(UUID.randomUUID().toString(), LocalDate.now(), productoId, BigDecimal.valueOf(100),
                List.of(new DetalleSalidaRequest(presentacionId, 12)), null,
                List.of(new InsumoUsadoRequest(inactivo.getId(), BigDecimal.ONE)));

        assertThatThrownBy(() -> service.crear(dto)).isInstanceOf(RecursoInactivoException.class);
        verify(recepcionPlantaRepository, never()).buscarConSaldoDisponibleOrdenadasPorAntiguedad();
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void rechazaUnInsumoRepetidoEnElMismoLote() {
        Producto producto = productoConId();
        when(productoRepository.findById(productoId)).thenReturn(Optional.of(producto));
        when(presentacionRepository.findById(presentacionId)).thenReturn(Optional.of(presentacionUnidad(presentacionId, producto)));
        Insumo cuajo = insumo("Cuajo", true);
        when(insumoRepository.findById(cuajo.getId())).thenReturn(Optional.of(cuajo));

        var dto = new CrearLoteRequest(UUID.randomUUID().toString(), LocalDate.now(), productoId, BigDecimal.valueOf(100),
                List.of(new DetalleSalidaRequest(presentacionId, 12)), null,
                List.of(new InsumoUsadoRequest(cuajo.getId(), BigDecimal.ONE), new InsumoUsadoRequest(cuajo.getId(), BigDecimal.TEN)));

        assertThatThrownBy(() -> service.crear(dto))
                .isInstanceOf(ValidacionNegocioException.class)
                .hasMessageContaining("más de una vez");
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void rechazaUnaPresentacionQueNoPerteneceAlProductoDelLote() {
        Producto yogurt = productoConId();
        Producto otro = new Producto();
        otro.setId(UUID.randomUUID());
        otro.setNombre("Otro producto");
        when(productoRepository.findById(productoId)).thenReturn(Optional.of(yogurt));
        when(presentacionRepository.findById(presentacionId)).thenReturn(Optional.of(presentacionUnidad(presentacionId, otro)));

        var dto = new CrearLoteRequest(UUID.randomUUID().toString(), LocalDate.now(), productoId, BigDecimal.valueOf(100),
                List.of(new DetalleSalidaRequest(presentacionId, 10)));

        assertThatThrownBy(() -> service.crear(dto))
                .isInstanceOf(ValidacionNegocioException.class)
                .hasMessageContaining("no pertenece al producto");
        verify(recepcionPlantaRepository, never()).buscarConSaldoDisponibleOrdenadasPorAntiguedad();
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void rechazaUnaPresentacionDuplicadaEnElMismoLote() {
        Producto producto = productoConId();
        when(productoRepository.findById(productoId)).thenReturn(Optional.of(producto));
        when(presentacionRepository.findById(presentacionId)).thenReturn(Optional.of(presentacionUnidad(presentacionId, producto)));

        var dto = new CrearLoteRequest(UUID.randomUUID().toString(), LocalDate.now(), productoId, BigDecimal.valueOf(100),
                List.of(new DetalleSalidaRequest(presentacionId, 10), new DetalleSalidaRequest(presentacionId, 5)));

        assertThatThrownBy(() -> service.crear(dto))
                .isInstanceOf(ValidacionNegocioException.class)
                .hasMessageContaining("más de una vez");
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void rechazaUnaPresentacionDesactivada() {
        Producto producto = productoConId();
        Presentacion inactiva = presentacionUnidad(presentacionId, producto);
        inactiva.setActivo(false);
        when(productoRepository.findById(productoId)).thenReturn(Optional.of(producto));
        when(presentacionRepository.findById(presentacionId)).thenReturn(Optional.of(inactiva));

        var dto = new CrearLoteRequest(UUID.randomUUID().toString(), LocalDate.now(), productoId, BigDecimal.valueOf(100),
                List.of(new DetalleSalidaRequest(presentacionId, 10)));

        assertThatThrownBy(() -> service.crear(dto)).isInstanceOf(RecursoInactivoException.class);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void rechazaUnProductoDesactivadoAntesDeValidarPresentacionesOLeche() {
        Producto producto = productoConId();
        producto.setActivo(false);
        when(productoRepository.findById(productoId)).thenReturn(Optional.of(producto));

        var dto = new CrearLoteRequest(UUID.randomUUID().toString(), LocalDate.now(), productoId, BigDecimal.valueOf(100),
                List.of(new DetalleSalidaRequest(presentacionId, 10)));

        assertThatThrownBy(() -> service.crear(dto)).isInstanceOf(RecursoInactivoException.class);
        verify(presentacionRepository, never()).findById(any());
        verify(recepcionPlantaRepository, never()).buscarConSaldoDisponibleOrdenadasPorAntiguedad();
    }

    // Flujo real, ya existente antes de esta fase: una produccion que consumio leche pero no obtuvo ningun
    // rendimiento aprovechable (lote fallido). Se preserva como lista de detalles VACIA -- nunca una linea
    // en cero -- sin generar ningun movimiento de inventario.
    @Test
    void unaListaDeDetallesVaciaRepresentaUnaProduccionSinNingunRendimientoYNoGeneraMovimientos() {
        Producto producto = productoConId();
        when(productoRepository.findById(productoId)).thenReturn(Optional.of(producto));
        RecepcionPlanta r1 = recepcion(BigDecimal.valueOf(100), LocalDate.now(), LocalDateTime.now());
        stubRecepciones(List.of(r1));
        stubLedgerDeConsumoDinamico();
        stubUsuarioAutenticado();
        stubGuardarLoteConId();
        stubGuardarDetalleConId();
        stubSinTrazabilidadPrevia();

        var dto = new CrearLoteRequest(UUID.randomUUID().toString(), LocalDate.now(), productoId, BigDecimal.valueOf(100), List.of());

        var resultado = service.crear(dto);

        assertThat(resultado.detalles()).isEmpty();
        assertThat(resultado.rendimientoPct()).isNull();
        assertThat(resultado.unidadesEsperadas()).isNull();
        verify(inventarioLecheService, times(1)).registrarSalidaProduccion(any(), eq(BigDecimal.valueOf(100)));
        verify(detalleRepository, never()).save(any());
        verify(inventarioService, never()).registrarEntradaProduccion(any());
        verify(presentacionRepository, never()).findById(any());
    }

    // ---- Rendimiento: solo se calcula con UNA salida sin contenido declarado (shape "Unidad"/"Pieza") Y
    // rendimientoEsperadoPct configurado -- nunca solo por tener una unica salida. ----

    @Test
    void calculaRendimientoConUnaUnicaSalidaSinContenidoDeclaradoYRendimientoEsperadoConfigurado() {
        Producto quesoFresco = productoConId(); // 12% esperado
        when(productoRepository.findById(productoId)).thenReturn(Optional.of(quesoFresco));
        when(presentacionRepository.findById(presentacionId)).thenReturn(Optional.of(presentacionUnidad(presentacionId, quesoFresco)));
        RecepcionPlanta r1 = recepcion(BigDecimal.valueOf(300), LocalDate.now(), LocalDateTime.now());
        stubRecepciones(List.of(r1));
        stubLedgerDeConsumoDinamico();
        stubUsuarioAutenticado();
        stubGuardarLoteConId();
        stubGuardarDetalleConId();
        stubSinTrazabilidadPrevia();

        var dto = new CrearLoteRequest(UUID.randomUUID().toString(), LocalDate.now(), productoId, BigDecimal.valueOf(300),
                List.of(new DetalleSalidaRequest(presentacionId, 218)));

        var resultado = service.crear(dto);

        assertThat(resultado.unidadesEsperadas()).isEqualTo(36); // 300 * 12 / 100, solo referencia
        assertThat(resultado.detalles()).hasSize(1);
        assertThat(resultado.detalles().get(0).cantidadObtenida()).isEqualTo(218);

        ArgumentCaptor<LoteProduccionDetalle> captor = ArgumentCaptor.forClass(LoteProduccionDetalle.class);
        verify(inventarioService).registrarEntradaProduccion(captor.capture());
        assertThat(captor.getValue().getCantidadObtenida()).isEqualTo(218); // el inventario suma lo REAL
    }

    @Test
    void calculaUnidadesEsperadasConElRendimientoEsperadoDelProducto() {
        Producto quesoFresco = productoConId();
        quesoFresco.setRendimientoEsperadoPct(BigDecimal.valueOf(150));
        when(productoRepository.findById(productoId)).thenReturn(Optional.of(quesoFresco));
        when(presentacionRepository.findById(presentacionId)).thenReturn(Optional.of(presentacionUnidad(presentacionId, quesoFresco)));
        RecepcionPlanta r1 = recepcion(BigDecimal.valueOf(300), LocalDate.now(), LocalDateTime.now());
        stubRecepciones(List.of(r1));
        stubLedgerDeConsumoDinamico();
        stubUsuarioAutenticado();
        stubGuardarLoteConId();
        stubGuardarDetalleConId();
        stubSinTrazabilidadPrevia();

        var dto = new CrearLoteRequest(UUID.randomUUID().toString(), LocalDate.now(), productoId, BigDecimal.valueOf(150),
                List.of(new DetalleSalidaRequest(presentacionId, 218)));

        var resultado = service.crear(dto);

        assertThat(resultado.unidadesEsperadas()).isEqualTo(225);
        assertThat(resultado.rendimientoEsperadoPctSnapshot()).isEqualByComparingTo(BigDecimal.valueOf(150));
        assertThat(resultado.rendimientoPct()).isEqualByComparingTo(BigDecimal.valueOf(145.33));
    }

    @Test
    void noCalculaRendimientoSiElProductoNoTieneRendimientoEsperadoConfigurado() {
        Producto yogurtSinRendimiento = productoConId();
        yogurtSinRendimiento.setRendimientoEsperadoPct(null);
        when(productoRepository.findById(productoId)).thenReturn(Optional.of(yogurtSinRendimiento));
        when(presentacionRepository.findById(presentacionId)).thenReturn(Optional.of(presentacionUnidad(presentacionId, yogurtSinRendimiento)));
        RecepcionPlanta r1 = recepcion(BigDecimal.valueOf(300), LocalDate.now(), LocalDateTime.now());
        stubRecepciones(List.of(r1));
        stubLedgerDeConsumoDinamico();
        stubUsuarioAutenticado();
        stubGuardarLoteConId();
        stubGuardarDetalleConId();
        stubSinTrazabilidadPrevia();

        var dto = new CrearLoteRequest(UUID.randomUUID().toString(), LocalDate.now(), productoId, BigDecimal.valueOf(100),
                List.of(new DetalleSalidaRequest(presentacionId, 95)));

        var resultado = service.crear(dto); // no debe lanzar NullPointerException

        assertThat(resultado.unidadesEsperadas()).isNull();
        assertThat(resultado.rendimientoEsperadoPctSnapshot()).isNull();
        assertThat(resultado.rendimientoPct()).isNull();
    }

    // Tener una sola salida NO basta si esa presentacion tiene contenido declarado (ej. "Botella 1 L"):
    // mezclar litros con el % pensado para piezas contables inventaria una equivalencia que no existe.
    @Test
    void noCalculaRendimientoConUnaUnicaSalidaSiSuPresentacionTieneContenidoDeclarado() {
        Producto quesoFresco = productoConId(); // 12% esperado
        when(productoRepository.findById(productoId)).thenReturn(Optional.of(quesoFresco));
        when(presentacionRepository.findById(presentacionId))
                .thenReturn(Optional.of(presentacionConContenido(presentacionId, "Botella 1 L", quesoFresco)));
        RecepcionPlanta r1 = recepcion(BigDecimal.valueOf(300), LocalDate.now(), LocalDateTime.now());
        stubRecepciones(List.of(r1));
        stubLedgerDeConsumoDinamico();
        stubUsuarioAutenticado();
        stubGuardarLoteConId();
        stubGuardarDetalleConId();
        stubSinTrazabilidadPrevia();

        var dto = new CrearLoteRequest(UUID.randomUUID().toString(), LocalDate.now(), productoId, BigDecimal.valueOf(300),
                List.of(new DetalleSalidaRequest(presentacionId, 218)));

        var resultado = service.crear(dto);

        assertThat(resultado.rendimientoPct()).isNull();
        assertThat(resultado.unidadesEsperadas()).isNull();
        assertThat(resultado.rendimientoEsperadoPctSnapshot()).isNull();
    }

    // ---- Concurrencia: nunca dos producciones consumen el mismo litro, nunca saldo negativo (sin cambios) ----

    @Test
    void dosProduccionesConcurrentesSobreElMismoSaldoSoloUnaTieneExito() throws InterruptedException {
        Producto producto = productoConId();
        RecepcionPlanta r1 = recepcion(BigDecimal.valueOf(30), LocalDate.now(), LocalDateTime.now());
        when(recepcionPlantaRepository.buscarConSaldoDisponibleOrdenadasPorAntiguedad()).thenReturn(List.of(r1));

        ReentrantLock lockFila = new ReentrantLock();
        Map<UUID, BigDecimal> consumido = Collections.synchronizedMap(new HashMap<>());

        when(recepcionPlantaRepository.bloquearPorIds(any())).thenAnswer(inv -> {
            lockFila.lock();
            return List.of(r1);
        });
        when(consumoRepository.sumarLitrosConsumidosPorRecepcion(r1.getId()))
                .thenAnswer(inv -> consumido.getOrDefault(r1.getId(), BigDecimal.ZERO));

        when(productoRepository.findById(productoId)).thenReturn(Optional.of(producto));
        lenient().when(presentacionRepository.findById(presentacionId)).thenReturn(Optional.of(presentacionUnidad(presentacionId, producto)));
        stubUsuarioAutenticado();
        stubGuardarLoteConId();
        stubGuardarDetalleConId();
        stubSinTrazabilidadPrevia();

        List<Boolean> resultados = Collections.synchronizedList(new ArrayList<>());
        Runnable produccionDe20 = () -> {
            try {
                var dto = new CrearLoteRequest(UUID.randomUUID().toString(), LocalDate.now(), productoId,
                        BigDecimal.valueOf(20), List.of(new DetalleSalidaRequest(presentacionId, 5)));
                service.crear(dto);
                consumido.merge(r1.getId(), BigDecimal.valueOf(20), BigDecimal::add); // equivalente al commit
                resultados.add(true);
            } catch (ValidacionNegocioException ex) {
                resultados.add(false);
            } finally {
                if (lockFila.isHeldByCurrentThread()) {
                    lockFila.unlock();
                }
            }
        };

        SecurityContextHolder.setStrategyName(SecurityContextHolder.MODE_INHERITABLETHREADLOCAL);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("produccion@planta.com", null));
        try {
            ExecutorService pool = Executors.newFixedThreadPool(2);
            pool.submit(produccionDe20);
            pool.submit(produccionDe20);
            pool.shutdown();
            assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            SecurityContextHolder.setStrategyName(SecurityContextHolder.MODE_THREADLOCAL);
        }

        assertThat(resultados).containsExactlyInAnyOrder(true, false);
        assertThat(consumido.get(r1.getId())).isEqualByComparingTo(BigDecimal.valueOf(20)); // nunca 40 (saldo era 30)
    }

    // ---- Idempotencia por uuidCliente ----

    @Test
    void esIdempotentePorUuidClienteConElMismoContenidoNoDuplicaNada() {
        String uuidCliente = UUID.randomUUID().toString();
        Producto producto = productoConId();
        LoteProduccion existente = new LoteProduccion();
        existente.setId(UUID.randomUUID());
        existente.setFecha(LocalDate.now());
        existente.setProducto(producto);
        existente.setLitrosUsados(BigDecimal.valueOf(150));
        when(repository.findByUuidCliente(uuidCliente)).thenReturn(Optional.of(existente));
        LoteProduccionDetalle detalleExistente = new LoteProduccionDetalle();
        detalleExistente.setPresentacion(presentacionUnidad(presentacionId, producto));
        detalleExistente.setCantidadObtenida(20);
        when(detalleRepository.findByLoteId(existente.getId())).thenReturn(List.of(detalleExistente));
        when(consumoRepository.findByLoteProduccionId(existente.getId())).thenReturn(List.of());

        var dto = new CrearLoteRequest(uuidCliente, existente.getFecha(), productoId, BigDecimal.valueOf(150),
                List.of(new DetalleSalidaRequest(presentacionId, 20)));

        var resultado = service.crear(dto);

        assertThat(resultado.id()).isEqualTo(existente.getId());
        verify(repository, never()).saveAndFlush(any());
        verify(productoRepository, never()).findById(any());
        verify(recepcionPlantaRepository, never()).buscarConSaldoDisponibleOrdenadasPorAntiguedad();
        verify(consumoRepository, never()).save(any());
        verify(inventarioLecheService, never()).registrarSalidaProduccion(any(), any());
        verify(inventarioService, never()).registrarEntradaProduccion(any());
    }

    // Mismo uuidCliente, contenido DISTINTO: conflicto explicito, el lote existente no se modifica.
    @Test
    void mismoUuidClienteConContenidoDistintoLanzaConflictoSinModificarElLoteExistente() {
        String uuidCliente = UUID.randomUUID().toString();
        Producto producto = productoConId();
        LoteProduccion existente = new LoteProduccion();
        existente.setId(UUID.randomUUID());
        existente.setFecha(LocalDate.now());
        existente.setProducto(producto);
        existente.setLitrosUsados(BigDecimal.valueOf(150));
        when(repository.findByUuidCliente(uuidCliente)).thenReturn(Optional.of(existente));
        LoteProduccionDetalle detalleExistente = new LoteProduccionDetalle();
        detalleExistente.setPresentacion(presentacionUnidad(presentacionId, producto));
        detalleExistente.setCantidadObtenida(20);
        when(detalleRepository.findByLoteId(existente.getId())).thenReturn(List.of(detalleExistente));

        // Misma fecha/producto/litros, pero cantidad DISTINTA (25 en vez de 20).
        var dto = new CrearLoteRequest(uuidCliente, existente.getFecha(), productoId, BigDecimal.valueOf(150),
                List.of(new DetalleSalidaRequest(presentacionId, 25)));

        assertThatThrownBy(() -> service.crear(dto))
                .isInstanceOf(ConflictoNegocioException.class)
                .hasMessageContaining("datos distintos");
        verify(repository, never()).saveAndFlush(any());
        verify(inventarioService, never()).registrarEntradaProduccion(any());
    }

    @Test
    void unaCarreraConElMismoUuidClienteLanzaConflictoSinRecuperarNadaEnLaMismaTransaccion() {
        String uuidCliente = UUID.randomUUID().toString();
        when(repository.findByUuidCliente(uuidCliente)).thenReturn(Optional.empty());
        Producto producto = productoConId();
        when(productoRepository.findById(productoId)).thenReturn(Optional.of(producto));
        when(presentacionRepository.findById(presentacionId)).thenReturn(Optional.of(presentacionUnidad(presentacionId, producto)));
        RecepcionPlanta r1 = recepcion(BigDecimal.valueOf(300), LocalDate.now(), LocalDateTime.now());
        stubRecepciones(List.of(r1));
        when(consumoRepository.sumarLitrosConsumidosPorRecepcion(any())).thenReturn(BigDecimal.ZERO);
        stubUsuarioAutenticado();
        when(repository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("uuid_cliente duplicado"));

        var dto = new CrearLoteRequest(uuidCliente, LocalDate.now(), productoId, BigDecimal.valueOf(150),
                List.of(new DetalleSalidaRequest(presentacionId, 20)));

        assertThatThrownBy(() -> service.crear(dto))
                .isInstanceOf(ConflictoNegocioException.class);

        verify(repository, times(1)).findByUuidCliente(uuidCliente);
        verify(consumoRepository, never()).save(any());
        verify(inventarioLecheService, never()).registrarSalidaProduccion(any(), any());
        verify(inventarioService, never()).registrarEntradaProduccion(any());
        verify(detalleRepository, never()).save(any());
    }

    @Test
    void elSaldoDeCadaRecepcionSeLeeDespuesDeAdquirirElBloqueoNuncaAntes() {
        String uuidCliente = UUID.randomUUID().toString();
        when(repository.findByUuidCliente(uuidCliente)).thenReturn(Optional.empty());
        Producto producto = productoConId();
        when(productoRepository.findById(productoId)).thenReturn(Optional.of(producto));
        when(presentacionRepository.findById(presentacionId)).thenReturn(Optional.of(presentacionUnidad(presentacionId, producto)));
        RecepcionPlanta r1 = recepcion(BigDecimal.valueOf(100), LocalDate.now(), LocalDateTime.now());
        stubRecepciones(List.of(r1));
        when(consumoRepository.sumarLitrosConsumidosPorRecepcion(r1.getId())).thenReturn(BigDecimal.ZERO);
        stubUsuarioAutenticado();
        stubGuardarLoteConId();
        stubGuardarDetalleConId();
        stubSinTrazabilidadPrevia();

        var dto = new CrearLoteRequest(uuidCliente, LocalDate.now(), productoId, BigDecimal.valueOf(50),
                List.of(new DetalleSalidaRequest(presentacionId, 10)));
        service.crear(dto);

        InOrder orden = inOrder(recepcionPlantaRepository, consumoRepository);
        orden.verify(recepcionPlantaRepository).bloquearPorIds(any());
        orden.verify(consumoRepository).sumarLitrosConsumidosPorRecepcion(r1.getId());
    }

    // ---- Regresion: validaciones que ya existian ----

    @Test
    void rechazaSiElProductoNoExiste() {
        String uuidCliente = UUID.randomUUID().toString();
        when(repository.findByUuidCliente(uuidCliente)).thenReturn(Optional.empty());
        when(productoRepository.findById(productoId)).thenReturn(Optional.empty());

        var dto = new CrearLoteRequest(uuidCliente, LocalDate.now(), productoId, BigDecimal.valueOf(100),
                List.of(new DetalleSalidaRequest(presentacionId, 10)));

        assertThatThrownBy(() -> service.crear(dto)).isInstanceOf(RecursoNoEncontradoException.class);
        verify(repository, never()).saveAndFlush(any());
        verify(recepcionPlantaRepository, never()).buscarConSaldoDisponibleOrdenadasPorAntiguedad();
    }

    @Test
    void buscarSinFiltrosPropagaAmbosParametrosComoNull() {
        when(repository.buscarConFiltros(isNull(), isNull(), isNull(), isNull(), eq(pageable))).thenReturn(new PageImpl<>(List.of()));

        service.buscar(null, null, null, null, pageable);

        verify(repository).buscarConFiltros(null, null, null, null, pageable);
    }

    // Fase 1 (control de vencimiento): el registro de un solo paso (camino compartido) no registra fechas de
    // vencimiento, asi que un producto controlado se rechaza ANTES de tocar leche, insumos o inventario.
    @Test
    void rechazaElRegistroDeUnPasoDeUnProductoQueControlaVencimiento() {
        Producto yogurt = productoConId();
        yogurt.setControlaVencimiento(true);
        when(productoRepository.findById(productoId)).thenReturn(Optional.of(yogurt));
        when(presentacionRepository.findById(presentacionId)).thenReturn(Optional.of(presentacionUnidad(presentacionId, yogurt)));

        var dto = new CrearLoteRequest(UUID.randomUUID().toString(), LocalDate.now(), productoId, BigDecimal.valueOf(100),
                List.of(new DetalleSalidaRequest(presentacionId, 10)));

        assertThatThrownBy(() -> service.crear(dto))
                .isInstanceOf(ValidacionNegocioException.class)
                .hasMessageContaining("controla vencimiento");
        verify(recepcionPlantaRepository, never()).buscarConSaldoDisponibleOrdenadasPorAntiguedad();
        verify(repository, never()).saveAndFlush(any());
        verify(inventarioService, never()).registrarEntradaProduccion(any());
    }

    @Test
    void buscarPliegaElTextoSinAcentosNiMayusculas() {
        when(repository.buscarConFiltros(isNull(), isNull(), isNull(), eq("%pain%"), eq(pageable))).thenReturn(new PageImpl<>(List.of()));

        service.buscar(null, null, null, "  Paín ", pageable);

        verify(repository).buscarConFiltros(null, null, null, "%pain%", pageable);
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
        return s;
    }
}
