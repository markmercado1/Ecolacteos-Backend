package com.planta.acopio.service;

import com.planta.acopio.dto.CrearVentaCarritoRequest;
import com.planta.acopio.dto.DetalleVentaRequest;
import com.planta.acopio.dto.VentaRequest;
import com.planta.acopio.entity.Presentacion;
import com.planta.acopio.entity.Producto;
import com.planta.acopio.entity.Usuario;
import com.planta.acopio.entity.Venta;
import com.planta.acopio.entity.VentaDetalle;
import com.planta.acopio.exception.ConflictoNegocioException;
import com.planta.acopio.exception.RecursoInactivoException;
import com.planta.acopio.exception.RecursoNoEncontradoException;
import com.planta.acopio.exception.ValidacionNegocioException;
import com.planta.acopio.repository.PresentacionRepository;
import com.planta.acopio.repository.UsuarioRepository;
import com.planta.acopio.repository.VentaDetalleRepository;
import com.planta.acopio.repository.VentaRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// Bloque 5 (confirmado): filtros opcionales combinables. Paginacion real: buscar() devuelve
// Page<VentaResumenResponse>.
// Fase 3 (Inventario por presentacion): VentaRequest habla en terminos de presentacionId directamente.
// Fase 4 (Venta multi-linea / carrito): el contrato movil (VentaRequest/VentaResponse) se conserva sin
// cambios -- internamente construye una venta de una sola linea a traves del mismo `crear()` compartido que
// usa el carrito nuevo (CrearVentaCarritoRequest). Idempotencia: mismo uuidCliente + mismo contenido ->
// recupera la venta existente; contenido distinto -> ConflictoNegocioException (409), mismo criterio que
// LoteProduccionService.
@ExtendWith(MockitoExtension.class)
class VentaServiceTest {

    @Mock private VentaRepository repository;
    @Mock private VentaDetalleRepository detalleRepository;
    @Mock private PresentacionRepository presentacionRepository;
    @Mock private UsuarioRepository usuarioRepository;
    @Mock private EntityManager entityManager;
    @Mock private PrecioQuesoService precioQuesoService;
    @Mock private InventarioService inventarioService;
    @Mock private ReservaService reservaService;
    @Mock private ConfiguracionIgvService igvService;
    @Mock private CompradorService compradorService;

    private VentaService service;
    private final Pageable pageable = PageRequest.of(0, 20);
    private final UUID productoId = UUID.randomUUID();
    private final UUID presentacionId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new VentaService(repository, detalleRepository, presentacionRepository, usuarioRepository,
                entityManager, precioQuesoService, inventarioService, reservaService, igvService, compradorService, TiposPrecioSimulados.activos());
    }

    private Producto productoActivo() {
        Producto t = new Producto();
        t.setId(productoId);
        t.setNombre("Queso Fresco");
        t.setActivo(true);
        return t;
    }

    private Presentacion presentacionActiva(Producto producto) {
        Presentacion p = new Presentacion();
        p.setId(presentacionId);
        p.setNombre("Unidad");
        p.setProducto(producto);
        p.setActivo(true);
        return p;
    }

    private VentaRequest ventaRequest(String uuidCliente, int cantidad, BigDecimal precioManipulado) {
        return new VentaRequest(uuidCliente, LocalDate.of(2026, 9, 18), "PUBLICO", presentacionId, cantidad, precioManipulado);
    }

    private void stubUsuarioAutenticado() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("ventas@planta.com", null));
        Usuario vendedor = new Usuario();
        vendedor.setId(UUID.randomUUID());
        vendedor.setEmail("ventas@planta.com");
        when(usuarioRepository.findByEmail("ventas@planta.com")).thenReturn(Optional.of(vendedor));
    }

    private void stubGuardadoDeVentaYDetalle() {
        when(repository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        when(detalleRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    // toResponse() (contrato movil, una sola linea) exige encontrar EXACTAMENTE una VentaDetalle al volver a
    // leerla -- este dummy solo satisface esa lectura posterior a crear(); los valores no se usan en las
    // aserciones de los tests que lo stubean (esas verifican el argumento capturado en save/saveAndFlush,
    // que ocurre ANTES de esta relectura).
    private void stubRelecturaDeUnaSolaLinea() {
        VentaDetalle detalle = new VentaDetalle();
        detalle.setPresentacion(presentacionActiva(productoActivo()));
        detalle.setCantidad(1);
        detalle.setPrecioUnitario(BigDecimal.ZERO);
        when(detalleRepository.findByVentaId(any())).thenReturn(List.of(detalle));
    }

    @Test
    void buscarSinFiltrosPropagaAmbosParametrosComoNull() {
        when(repository.buscarConFiltros(isNull(), isNull(), isNull(), isNull(), eq(pageable))).thenReturn(new PageImpl<>(List.of()));

        service.buscar(null, null, null, pageable);

        verify(repository).buscarConFiltros(null, null, null, null, pageable);
    }

    // El repositorio recibe tipoCliente como String (necesario para la query
    // nativa -- ver comentario en VentaRepository), pero el service igual
    // valida que sea un valor legal del enum antes de pasarlo.
    @Test
    void buscarNormalizaElTipoClienteAntesDeConsultar() {
        LocalDate fecha = LocalDate.of(2026, 9, 10);
        when(repository.buscarConFiltros(fecha, fecha, "MAYORISTA", null, pageable)).thenReturn(new PageImpl<>(List.of()));

        service.buscar(fecha, "MAYORISTA", null, pageable);

        verify(repository).buscarConFiltros(fecha, fecha, "MAYORISTA", null, pageable);
    }

    @Test
    void buscarRechazaUnTipoClienteQueNoExisteEnElCatalogo() {
        assertThatThrownBy(() -> service.buscar(null, "NO_EXISTE", null, pageable))
                .isInstanceOf(com.planta.acopio.exception.ValidacionNegocioException.class);
    }

    // Confirma que la metadata de paginacion (pagina/tamano/total) se
    // propaga correctamente desde el Page de Spring Data hasta la respuesta.
    @Test
    void buscarPropagaLaMetadataDePaginacion() {
        var pageableChico = PageRequest.of(1, 2);
        var paginaSimulada = new PageImpl<Venta>(List.of(), pageableChico, 5);
        when(repository.buscarConFiltros(isNull(), isNull(), isNull(), isNull(), eq(pageableChico))).thenReturn(paginaSimulada);

        var resultado = service.buscar(null, null, null, pageableChico);

        assertThat(resultado.getNumber()).isEqualTo(1);
        assertThat(resultado.getSize()).isEqualTo(2);
        assertThat(resultado.getTotalElements()).isEqualTo(5);
        assertThat(resultado.getTotalPages()).isEqualTo(3);
    }

    // Autoridad de precio (Bloque 5, requisito #17 "Ventas"): aunque el cliente envie un precioUnitario
    // manipulado, la venta se persiste con el precio que el backend resuelve como vigente.
    @Test
    void registrarUnaVentaUsaElPrecioVigenteDelBackendIgnorandoElDelCliente() {
        String uuidCliente = UUID.randomUUID().toString();
        when(repository.findByUuidCliente(uuidCliente)).thenReturn(Optional.empty());
        Producto producto = productoActivo();
        Presentacion presentacion = presentacionActiva(producto);
        when(presentacionRepository.findById(presentacionId)).thenReturn(Optional.of(presentacion));
        when(precioQuesoService.resolverPrecioVigente(eq(presentacion), any(), eq(LocalDate.of(2026, 9, 18))))
                .thenReturn(BigDecimal.valueOf(15.00));
        stubUsuarioAutenticado();
        stubGuardadoDeVentaYDetalle();
        stubRelecturaDeUnaSolaLinea();

        var dto = ventaRequest(uuidCliente, 10, BigDecimal.valueOf(1)); // 1.00 = precio manipulado por el cliente

        service.registrarOIgnorarSiDuplicado(dto);

        ArgumentCaptor<VentaDetalle> captor = ArgumentCaptor.forClass(VentaDetalle.class);
        verify(detalleRepository).save(captor.capture());
        assertThat(captor.getValue().getPrecioUnitario()).isEqualByComparingTo("15.00");
    }

    // Stock (requisito #6/#8): el backend rechaza la venta si no hay stock suficiente, y no debe quedar
    // ni la venta ni el movimiento de inventario guardados (se valida ANTES de persistir la venta).
    @Test
    void rechazaLaVentaSiElStockEsInsuficienteYNoPersisteNada() {
        String uuidCliente = UUID.randomUUID().toString();
        when(repository.findByUuidCliente(uuidCliente)).thenReturn(Optional.empty());
        Producto producto = productoActivo();
        Presentacion presentacion = presentacionActiva(producto);
        when(presentacionRepository.findById(presentacionId)).thenReturn(Optional.of(presentacion));
        when(precioQuesoService.resolverPrecioVigente(eq(presentacion), any(), any())).thenReturn(BigDecimal.valueOf(15.00));
        doThrow(new ValidacionNegocioException("Stock insuficiente de Unidad: disponibles 5, solicitadas 60"))
                .when(inventarioService).validarStockSuficiente(presentacionId, 60);

        var dto = ventaRequest(uuidCliente, 60, BigDecimal.valueOf(15));

        assertThatThrownBy(() -> service.registrarOIgnorarSiDuplicado(dto))
                .isInstanceOf(ValidacionNegocioException.class)
                .hasMessageContaining("Stock insuficiente");

        verify(repository, never()).saveAndFlush(any());
        verify(inventarioService, never()).registrarSalidaVenta(any());
    }

    // Precios (requisito: "no hay precio configurado" es una regla de negocio, no un 500): si no existe
    // un precio vigente, la venta se rechaza ANTES de validar stock o persistir nada.
    @Test
    void rechazaLaVentaSiNoHayPrecioConfiguradoYNoValidaStockNiPersisteNada() {
        String uuidCliente = UUID.randomUUID().toString();
        when(repository.findByUuidCliente(uuidCliente)).thenReturn(Optional.empty());
        Producto producto = productoActivo();
        Presentacion presentacion = presentacionActiva(producto);
        when(presentacionRepository.findById(presentacionId)).thenReturn(Optional.of(presentacion));
        when(precioQuesoService.resolverPrecioVigente(eq(presentacion), any(), any()))
                .thenThrow(new ValidacionNegocioException("No hay un precio configurado para Unidad en modalidad PUBLICO"));

        var dto = ventaRequest(uuidCliente, 5, BigDecimal.valueOf(15));

        assertThatThrownBy(() -> service.registrarOIgnorarSiDuplicado(dto))
                .isInstanceOf(ValidacionNegocioException.class)
                .hasMessageContaining("No hay un precio configurado");

        verify(inventarioService, never()).validarStockSuficiente(any(UUID.class), anyInt());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void rechazaLaVentaDeUnaPresentacionDesactivadaAntesDeResolverPrecioOStock() {
        String uuidCliente = UUID.randomUUID().toString();
        when(repository.findByUuidCliente(uuidCliente)).thenReturn(Optional.empty());
        Producto producto = productoActivo();
        Presentacion presentacion = presentacionActiva(producto);
        presentacion.setActivo(false);
        when(presentacionRepository.findById(presentacionId)).thenReturn(Optional.of(presentacion));

        var dto = ventaRequest(uuidCliente, 5, BigDecimal.valueOf(15));

        assertThatThrownBy(() -> service.registrarOIgnorarSiDuplicado(dto))
                .isInstanceOf(RecursoInactivoException.class);

        verify(precioQuesoService, never()).resolverPrecioVigente(any(), any(), any());
        verify(inventarioService, never()).validarStockSuficiente(any(UUID.class), anyInt());
    }

    @Test
    void rechazaLaVentaDeUnaPresentacionCuyoProductoEstaDesactivado() {
        String uuidCliente = UUID.randomUUID().toString();
        when(repository.findByUuidCliente(uuidCliente)).thenReturn(Optional.empty());
        Producto producto = productoActivo();
        producto.setActivo(false);
        Presentacion presentacion = presentacionActiva(producto);
        when(presentacionRepository.findById(presentacionId)).thenReturn(Optional.of(presentacion));

        var dto = ventaRequest(uuidCliente, 5, BigDecimal.valueOf(15));

        assertThatThrownBy(() -> service.registrarOIgnorarSiDuplicado(dto))
                .isInstanceOf(RecursoInactivoException.class);

        verify(precioQuesoService, never()).resolverPrecioVigente(any(), any(), any());
        verify(inventarioService, never()).validarStockSuficiente(any(UUID.class), anyInt());
    }

    @Test
    void rechazaLaVentaSiLaPresentacionNoExiste() {
        String uuidCliente = UUID.randomUUID().toString();
        when(repository.findByUuidCliente(uuidCliente)).thenReturn(Optional.empty());
        when(presentacionRepository.findById(presentacionId)).thenReturn(Optional.empty());

        var dto = ventaRequest(uuidCliente, 5, BigDecimal.valueOf(15));

        assertThatThrownBy(() -> service.registrarOIgnorarSiDuplicado(dto))
                .isInstanceOf(RecursoNoEncontradoException.class);

        verify(precioQuesoService, never()).resolverPrecioVigente(any(), any(), any());
        verify(repository, never()).saveAndFlush(any());
    }

    // Venta -> inventario (requisito #8): una venta valida genera automaticamente la salida de stock,
    // dentro de la misma operacion (misma transaccion en el service real). Fase 3: el stock es por
    // PRESENTACION (no por producto). Fase 4: la salida se liga a la VentaDetalle, no a la Venta directamente.
    @Test
    void registrarUnaVentaValidaGeneraAutomaticamenteLaSalidaDeInventarioPorPresentacion() {
        String uuidCliente = UUID.randomUUID().toString();
        when(repository.findByUuidCliente(uuidCliente)).thenReturn(Optional.empty());
        Producto producto = productoActivo();
        Presentacion presentacion = presentacionActiva(producto);
        when(presentacionRepository.findById(presentacionId)).thenReturn(Optional.of(presentacion));
        when(precioQuesoService.resolverPrecioVigente(eq(presentacion), any(), any())).thenReturn(BigDecimal.valueOf(15.00));
        stubUsuarioAutenticado();
        stubGuardadoDeVentaYDetalle();
        stubRelecturaDeUnaSolaLinea();

        var dto = ventaRequest(uuidCliente, 10, BigDecimal.valueOf(15));

        service.registrarOIgnorarSiDuplicado(dto);

        verify(inventarioService).validarStockSuficiente(presentacionId, 10);
        ArgumentCaptor<VentaDetalle> captor = ArgumentCaptor.forClass(VentaDetalle.class);
        verify(inventarioService).registrarSalidaVenta(captor.capture());
        assertThat(captor.getValue().getCantidad()).isEqualTo(10);
        assertThat(captor.getValue().getPresentacion()).isEqualTo(presentacion);
    }

    // Total: suma de (cantidad x precio resuelto) de todas las lineas -- ya no una columna GENERADA por
    // Postgres (no puede expresar una suma de filas de otra tabla), lo fija VentaService.
    @Test
    void elTotalDeUnaVentaDeUnaSolaLineaEsCantidadPorPrecioVigente() {
        String uuidCliente = UUID.randomUUID().toString();
        when(repository.findByUuidCliente(uuidCliente)).thenReturn(Optional.empty());
        Producto producto = productoActivo();
        Presentacion presentacion = presentacionActiva(producto);
        when(presentacionRepository.findById(presentacionId)).thenReturn(Optional.of(presentacion));
        when(precioQuesoService.resolverPrecioVigente(eq(presentacion), any(), any())).thenReturn(BigDecimal.valueOf(15.00));
        stubUsuarioAutenticado();
        stubGuardadoDeVentaYDetalle();
        stubRelecturaDeUnaSolaLinea();

        var dto = ventaRequest(uuidCliente, 10, BigDecimal.valueOf(15));

        service.registrarOIgnorarSiDuplicado(dto);

        ArgumentCaptor<Venta> captor = ArgumentCaptor.forClass(Venta.class);
        verify(repository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getTotal()).isEqualByComparingTo("150.00");
    }

    // Idempotencia (mismo contenido): si el uuidCliente ya existe con el MISMO contenido, se devuelve la
    // venta existente sin volver a resolver presentacion, precio, validar stock ni tocar el repositorio.
    @Test
    void siLaVentaYaExistePorUuidClienteConElMismoContenidoNoResuelveNadaDeNuevo() {
        String uuidCliente = UUID.randomUUID().toString();
        Venta existente = new Venta();
        existente.setId(UUID.randomUUID());
        existente.setFecha(LocalDate.of(2026, 9, 18));
        existente.setTipoCliente("PUBLICO");
        existente.setTotal(BigDecimal.valueOf(150));
        when(repository.findByUuidCliente(uuidCliente)).thenReturn(Optional.of(existente));

        VentaDetalle detalleExistente = new VentaDetalle();
        detalleExistente.setPresentacion(presentacionActiva(productoActivo()));
        detalleExistente.setCantidad(10);
        detalleExistente.setPrecioUnitario(BigDecimal.valueOf(15));
        when(detalleRepository.findByVentaId(existente.getId())).thenReturn(List.of(detalleExistente));

        var dto = ventaRequest(uuidCliente, 10, BigDecimal.valueOf(1));

        service.registrarOIgnorarSiDuplicado(dto);

        verify(precioQuesoService, never()).resolverPrecioVigente(any(), any(), any());
        verify(inventarioService, never()).validarStockSuficiente(any(UUID.class), anyInt());
        verify(repository, never()).saveAndFlush(any());
    }

    // Idempotencia (contenido distinto): mismo uuidCliente, pero una cantidad distinta a la ya confirmada ->
    // conflicto explicito, la venta existente NUNCA se modifica (mismo criterio que LoteProduccionService).
    @Test
    void siLaVentaYaExistePorUuidClienteConContenidoDistintoLanzaConflicto() {
        String uuidCliente = UUID.randomUUID().toString();
        Venta existente = new Venta();
        existente.setId(UUID.randomUUID());
        existente.setFecha(LocalDate.of(2026, 9, 18));
        existente.setTipoCliente("PUBLICO");
        existente.setTotal(BigDecimal.valueOf(150));
        when(repository.findByUuidCliente(uuidCliente)).thenReturn(Optional.of(existente));

        VentaDetalle detalleExistente = new VentaDetalle();
        detalleExistente.setPresentacion(presentacionActiva(productoActivo()));
        detalleExistente.setCantidad(10); // la solicitud de abajo pide 99, no 10
        detalleExistente.setPrecioUnitario(BigDecimal.valueOf(15));
        when(detalleRepository.findByVentaId(existente.getId())).thenReturn(List.of(detalleExistente));

        var dto = ventaRequest(uuidCliente, 99, BigDecimal.valueOf(1));

        assertThatThrownBy(() -> service.registrarOIgnorarSiDuplicado(dto))
                .isInstanceOf(ConflictoNegocioException.class);

        verify(repository, never()).saveAndFlush(any());
    }

    // --- Carrito multi-linea (Fase 4) ---

    private CrearVentaCarritoRequest carritoRequest(String uuidCliente, DetalleVentaRequest... detalles) {
        return new CrearVentaCarritoRequest(uuidCliente, LocalDate.of(2026, 9, 18), "PUBLICO", List.of(detalles));
    }

    @Test
    void registrarUnCarritoConVariasLineasValidaYDescuentaCadaPresentacionPorSeparado() {
        String uuidCliente = UUID.randomUUID().toString();
        UUID presentacion2Id = UUID.randomUUID();
        when(repository.findByUuidCliente(uuidCliente)).thenReturn(Optional.empty());

        Producto producto = productoActivo();
        Presentacion presentacion1 = presentacionActiva(producto);
        Presentacion presentacion2 = new Presentacion();
        presentacion2.setId(presentacion2Id);
        presentacion2.setNombre("Botella 1 L");
        presentacion2.setProducto(producto);
        presentacion2.setActivo(true);

        when(presentacionRepository.findById(presentacionId)).thenReturn(Optional.of(presentacion1));
        when(presentacionRepository.findById(presentacion2Id)).thenReturn(Optional.of(presentacion2));
        when(precioQuesoService.resolverPrecioVigente(eq(presentacion1), any(), any())).thenReturn(BigDecimal.valueOf(10.00));
        when(precioQuesoService.resolverPrecioVigente(eq(presentacion2), any(), any())).thenReturn(BigDecimal.valueOf(5.00));
        stubUsuarioAutenticado();
        stubGuardadoDeVentaYDetalle();
        when(detalleRepository.findByVentaId(any())).thenReturn(List.of());

        var dto = carritoRequest(uuidCliente,
                new DetalleVentaRequest(presentacionId, 3),
                new DetalleVentaRequest(presentacion2Id, 4));

        service.registrarCarritoOIgnorarSiDuplicado(dto);

        verify(inventarioService).validarStockSuficiente(presentacionId, 3);
        verify(inventarioService).validarStockSuficiente(presentacion2Id, 4);
        verify(detalleRepository, org.mockito.Mockito.times(2)).save(any());
        verify(inventarioService, org.mockito.Mockito.times(2)).registrarSalidaVenta(any());

        ArgumentCaptor<Venta> captor = ArgumentCaptor.forClass(Venta.class);
        verify(repository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getTotal()).isEqualByComparingTo("50.00"); // 3*10 + 4*5
    }

    @Test
    void rechazaUnCarritoConLaMismaPresentacionRepetidaEnDosLineas() {
        String uuidCliente = UUID.randomUUID().toString();
        when(repository.findByUuidCliente(uuidCliente)).thenReturn(Optional.empty());
        when(presentacionRepository.findById(presentacionId)).thenReturn(Optional.of(presentacionActiva(productoActivo())));

        var dto = carritoRequest(uuidCliente,
                new DetalleVentaRequest(presentacionId, 3),
                new DetalleVentaRequest(presentacionId, 4));

        assertThatThrownBy(() -> service.registrarCarritoOIgnorarSiDuplicado(dto))
                .isInstanceOf(ValidacionNegocioException.class)
                .hasMessageContaining("más de una vez");

        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void buscarPliegaElTextoSinAcentosNiMayusculas() {
        when(repository.buscarConFiltros(isNull(), isNull(), isNull(), eq("%freson%"), eq(pageable))).thenReturn(new PageImpl<>(List.of()));

        service.buscar(null, null, "  FRESÓN ", pageable);

        verify(repository).buscarConFiltros(null, null, null, "%freson%", pageable);
    }
}
