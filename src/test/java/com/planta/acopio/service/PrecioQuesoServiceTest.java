package com.planta.acopio.service;

import com.planta.acopio.dto.PrecioQuesoRequest;
import com.planta.acopio.entity.PrecioQueso;
import com.planta.acopio.entity.Presentacion;
import com.planta.acopio.entity.Producto;
import com.planta.acopio.exception.ConflictoNegocioException;
import com.planta.acopio.exception.RecursoNoEncontradoException;
import com.planta.acopio.exception.ValidacionNegocioException;
import com.planta.acopio.dto.FilaPrecioPresentacionResponse;
import com.planta.acopio.repository.PrecioQuesoRepository;
import com.planta.acopio.repository.PresentacionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// Fase 2 (Precios por presentacion, V11__precio_presentacion.sql): serie historica append-only por
// PRESENTACION + modalidad -- cada presentacion tiene sus propias tarifas, nunca se deduce el precio de
// una a partir de otra. No se exige configurar las tres modalidades.
@ExtendWith(MockitoExtension.class)
class PrecioQuesoServiceTest {

    @Mock private PrecioQuesoRepository repository;
    @Mock private PresentacionRepository presentacionRepository;

    private PrecioQuesoService service;
    private final UUID presentacionId = UUID.randomUUID();
    private final UUID otraPresentacionId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new PrecioQuesoService(repository, presentacionRepository, TiposPrecioSimulados.activos());
    }

    private Producto yogurt() {
        Producto p = new Producto();
        p.setId(UUID.randomUUID());
        p.setNombre("Yogurt de fresa");
        return p;
    }

    private Presentacion botella1L() {
        Presentacion p = new Presentacion();
        p.setId(presentacionId);
        p.setNombre("Botella 1 L");
        p.setProducto(yogurt());
        return p;
    }

    private Presentacion botella500ml() {
        Presentacion p = new Presentacion();
        p.setId(otraPresentacionId);
        p.setNombre("Botella 500 ml");
        p.setProducto(yogurt());
        return p;
    }

    @Test
    void crearRegistraUnPrecioNuevoParaUnaPresentacionYModalidad() {
        when(presentacionRepository.findById(presentacionId)).thenReturn(Optional.of(botella1L()));
        when(repository.existsByPresentacionIdAndModalidadAndVigenteDesde(presentacionId, "PUBLICO", LocalDate.of(2026, 9, 18)))
                .thenReturn(false);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var dto = new PrecioQuesoRequest(presentacionId, "PUBLICO", BigDecimal.valueOf(15.00), LocalDate.of(2026, 9, 18));

        var resultado = service.crear(dto);

        assertThat(resultado.presentacionNombre()).isEqualTo("Botella 1 L");
        assertThat(resultado.productoNombre()).isEqualTo("Yogurt de fresa");
        assertThat(resultado.modalidad()).isEqualTo("PUBLICO");
        assertThat(resultado.precio()).isEqualByComparingTo("15.00");
    }

    @Test
    void crearRechazaUnPrecioDuplicadoParaLaMismaPresentacionModalidadYVigencia() {
        when(presentacionRepository.findById(presentacionId)).thenReturn(Optional.of(botella1L()));
        when(repository.existsByPresentacionIdAndModalidadAndVigenteDesde(presentacionId, "PUBLICO", LocalDate.of(2026, 9, 18)))
                .thenReturn(true);

        var dto = new PrecioQuesoRequest(presentacionId, "PUBLICO", BigDecimal.valueOf(15.00), LocalDate.of(2026, 9, 18));

        assertThatThrownBy(() -> service.crear(dto)).isInstanceOf(ConflictoNegocioException.class);

        verify(repository, never()).save(any());
    }

    @Test
    void crearFallaSiLaPresentacionNoExiste() {
        when(presentacionRepository.findById(presentacionId)).thenReturn(Optional.empty());

        var dto = new PrecioQuesoRequest(presentacionId, "PUBLICO", BigDecimal.valueOf(15.00), LocalDate.of(2026, 9, 18));

        assertThatThrownBy(() -> service.crear(dto)).isInstanceOf(RecursoNoEncontradoException.class);
    }

    // Un mismo producto puede tener presentaciones distintas con precios distintos -- nunca se deduce el
    // precio de una a partir de otra (RF explicito de Fase 2).
    @Test
    void dosPresentacionesDelMismoProductoTienenPreciosIndependientesEntreSi() {
        when(presentacionRepository.findById(presentacionId)).thenReturn(Optional.of(botella1L()));
        when(presentacionRepository.findById(otraPresentacionId)).thenReturn(Optional.of(botella500ml()));
        when(repository.existsByPresentacionIdAndModalidadAndVigenteDesde(any(), any(), any())).thenReturn(false);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var precio1L = service.crear(new PrecioQuesoRequest(presentacionId, "PUBLICO", BigDecimal.valueOf(8.00), LocalDate.of(2026, 9, 18)));
        var precio500ml = service.crear(new PrecioQuesoRequest(otraPresentacionId, "PUBLICO", BigDecimal.valueOf(5.00), LocalDate.of(2026, 9, 18)));

        assertThat(precio1L.precio()).isEqualByComparingTo("8.00");
        assertThat(precio500ml.precio()).isEqualByComparingTo("5.00");
        assertThat(precio1L.presentacionId()).isNotEqualTo(precio500ml.presentacionId());
    }

    // Un mismo producto/presentacion puede tener precios distintos por modalidad -- no se pisan entre si.
    @Test
    void permitePreciosDistintosParaLaMismaPresentacionEnDistintasModalidades() {
        when(presentacionRepository.findById(presentacionId)).thenReturn(Optional.of(botella1L()));
        when(repository.existsByPresentacionIdAndModalidadAndVigenteDesde(any(), any(), any())).thenReturn(false);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var mayorista = service.crear(new PrecioQuesoRequest(presentacionId, "MAYORISTA", BigDecimal.valueOf(6.50), LocalDate.of(2026, 9, 18)));
        var publico = service.crear(new PrecioQuesoRequest(presentacionId, "PUBLICO", BigDecimal.valueOf(8.00), LocalDate.of(2026, 9, 18)));

        assertThat(mayorista.precio()).isEqualByComparingTo("6.50");
        assertThat(publico.precio()).isEqualByComparingTo("8.00");
    }

    // No se exige configurar las tres modalidades (RF explicito de Fase 2): una presentacion puede tener
    // precio solo para PUBLICO. resolverPrecioVigente para MAYORISTA debe fallar de forma clara, sin que
    // crear() para PUBLICO haya exigido nada mas.
    @Test
    void noExigeConfigurarLasTresModalidades() {
        when(presentacionRepository.findById(presentacionId)).thenReturn(Optional.of(botella1L()));
        when(repository.existsByPresentacionIdAndModalidadAndVigenteDesde(any(), any(), any())).thenReturn(false);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        service.crear(new PrecioQuesoRequest(presentacionId, "PUBLICO", BigDecimal.valueOf(8.00), LocalDate.of(2026, 9, 18)));

        when(repository.findVigente(presentacionId, "MAYORISTA", LocalDate.of(2026, 9, 18))).thenReturn(Optional.empty());
        when(repository.findPrimeraProgramada(presentacionId, "MAYORISTA", LocalDate.of(2026, 9, 18))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolverPrecioVigente(botella1L(), "MAYORISTA", LocalDate.of(2026, 9, 18)))
                .isInstanceOf(ValidacionNegocioException.class);
    }

    @Test
    void listarDevuelveElHistoricoTalCualLoDaElRepositorio() {
        PrecioQueso p = new PrecioQueso();
        p.setId(UUID.randomUUID());
        p.setPresentacion(botella1L());
        p.setModalidad("PUBLICO");
        p.setPrecio(BigDecimal.valueOf(15.00));
        p.setVigenteDesde(LocalDate.of(2026, 9, 18));
        when(repository.findAllByOrderByVigenteDesdeDesc()).thenReturn(List.of(p));

        var resultado = service.listar();

        assertThat(resultado).hasSize(1);
        assertThat(resultado.get(0).precio()).isEqualByComparingTo("15.00");
    }

    // Fase 2 (ampliacion): historial de UNA presentacion, usado por el dialogo "Ver historico" de /precios
    // en vez de traer el historico completo del catalogo para filtrarlo en el cliente.
    @Test
    void listarPorPresentacionDevuelveSoloElHistoricoDeEsaPresentacion() {
        when(presentacionRepository.existsById(presentacionId)).thenReturn(true);
        PrecioQueso p = new PrecioQueso();
        p.setId(UUID.randomUUID());
        p.setPresentacion(botella1L());
        p.setModalidad("PUBLICO");
        p.setPrecio(BigDecimal.valueOf(15.00));
        p.setVigenteDesde(LocalDate.of(2026, 9, 18));
        when(repository.findByPresentacionIdOrderByVigenteDesdeDesc(presentacionId)).thenReturn(List.of(p));

        var resultado = service.listarPorPresentacion(presentacionId);

        assertThat(resultado).hasSize(1);
        assertThat(resultado.get(0).presentacionId()).isEqualTo(presentacionId);
        verify(repository, never()).findAllByOrderByVigenteDesdeDesc();
    }

    @Test
    void listarPorPresentacionFallaSiLaPresentacionNoExiste() {
        when(presentacionRepository.existsById(presentacionId)).thenReturn(false);

        assertThatThrownBy(() -> service.listarPorPresentacion(presentacionId))
                .isInstanceOf(RecursoNoEncontradoException.class);
        verify(repository, never()).findByPresentacionIdOrderByVigenteDesdeDesc(any());
    }

    // El precio historico nunca se sobreescribe: resolverPrecioVigente siempre resuelve el de
    // vigenteDesde mas reciente <= la fecha dada (delegado en la query del repositorio).
    @Test
    void resolverPrecioVigenteDelegaEnElRepositorioYDevuelveElPrecioEncontrado() {
        PrecioQueso p = new PrecioQueso();
        p.setPrecio(BigDecimal.valueOf(15.00));
        when(repository.findVigente(presentacionId, "PUBLICO", LocalDate.of(2026, 9, 18)))
                .thenReturn(Optional.of(p));

        BigDecimal precio = service.resolverPrecioVigente(botella1L(), "PUBLICO", LocalDate.of(2026, 9, 18));

        assertThat(precio).isEqualByComparingTo("15.00");
    }

    @Test
    void resolverPrecioVigenteFallaConValidacionDeNegocioSiNuncaSeConfiguroUnPrecio() {
        when(repository.findVigente(any(), any(), any())).thenReturn(Optional.empty());
        when(repository.findPrimeraProgramada(any(), any(), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolverPrecioVigente(botella1L(), "PUBLICO", LocalDate.of(2026, 9, 18)))
                .isInstanceOf(ValidacionNegocioException.class)
                .hasMessageContaining("No hay un precio configurado");
    }

    // RF explicito de Fase 2, punto 7: "precio programado para una fecha futura" es un mensaje DISTINTO de
    // "nunca se configuro un precio" -- ambos son 400 (regla de negocio), pero no se confunden entre si.
    @Test
    void resolverPrecioVigenteDistingueUnPrecioProgramadoDeUnoNuncaConfigurado() {
        PrecioQueso futuro = new PrecioQueso();
        futuro.setVigenteDesde(LocalDate.of(2026, 10, 1));
        when(repository.findVigente(any(), any(), any())).thenReturn(Optional.empty());
        when(repository.findPrimeraProgramada(any(), any(), any())).thenReturn(Optional.of(futuro));

        assertThatThrownBy(() -> service.resolverPrecioVigente(botella1L(), "PUBLICO", LocalDate.of(2026, 9, 18)))
                .isInstanceOf(ValidacionNegocioException.class)
                .hasMessageContaining("programado")
                .hasMessageContaining("2026-10-01");
    }

    @Test
    void resolverParaConsultaFallaSiLaPresentacionNoExiste() {
        when(presentacionRepository.findById(presentacionId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolverParaConsulta(presentacionId, "PUBLICO", LocalDate.now()))
                .isInstanceOf(RecursoNoEncontradoException.class);
    }

    @Test
    void resolverParaConsultaDevuelveElPrecioVigenteParaLaModalidadPedida() {
        PrecioQueso p = new PrecioQueso();
        p.setId(UUID.randomUUID());
        p.setPresentacion(botella1L());
        p.setModalidad("MAYORISTA");
        p.setPrecio(BigDecimal.valueOf(13.50));
        p.setVigenteDesde(LocalDate.of(2026, 9, 1));
        when(presentacionRepository.findById(presentacionId)).thenReturn(Optional.of(botella1L()));
        when(repository.findVigente(presentacionId, "MAYORISTA", LocalDate.of(2026, 9, 18)))
                .thenReturn(Optional.of(p));

        var resultado = service.resolverParaConsulta(presentacionId, "MAYORISTA", LocalDate.of(2026, 9, 18));

        assertThat(resultado.precio()).isEqualByComparingTo("13.50");
        assertThat(resultado.modalidad()).isEqualTo("MAYORISTA");
    }

    // Cambio de fecha de venta: la misma presentacion/modalidad puede resolver un precio distinto (o
    // ninguno) segun la fecha consultada -- el criterio de resolverParaConsulta es siempre "vigenteDesde
    // mas reciente <= fecha", nunca "hoy" fijo (ver tambien VentaController, que ahora exige `fecha`).
    @Test
    void resolverParaConsultaUsaLaFechaRecibidaNoLaDeHoy() {
        when(presentacionRepository.findById(presentacionId)).thenReturn(Optional.of(botella1L()));
        PrecioQueso antiguo = new PrecioQueso();
        antiguo.setPresentacion(botella1L());
        antiguo.setModalidad("PUBLICO");
        antiguo.setPrecio(BigDecimal.valueOf(7.00));
        antiguo.setVigenteDesde(LocalDate.of(2025, 6, 1));
        when(repository.findVigente(presentacionId, "PUBLICO", LocalDate.of(2026, 1, 1)))
                .thenReturn(Optional.of(antiguo));

        var resultado = service.resolverParaConsulta(presentacionId, "PUBLICO", LocalDate.of(2026, 1, 1));

        assertThat(resultado.precio()).isEqualByComparingTo("7.00");
    }

    // Fase 2 (ampliacion: listado paginado de /admin/precios-queso/presentaciones). La consulta real contra
    // Postgres (LEFT JOIN, ordenamiento, UUID) se cubre en PreciosPorPresentacionPaginadoIntegrationTest;
    // aqui se verifica con mocks el ensamblado de la respuesta y la resolucion de estado por fila.
    private final UUID familiaId = UUID.randomUUID();

    private Object[] filaCon(UUID presentacionId) {
        return new Object[] { familiaId, "Yogures", UUID.randomUUID(), "Yogurt de fresa", presentacionId, "Botella 1 L" };
    }

    private Presentacion presentacionCon(UUID id) {
        Presentacion p = new Presentacion();
        p.setId(id);
        return p;
    }

    @Test
    void listarPaginadoResuelveVigenteYSinPrecioPorModalidadParaCadaFila() {
        UUID idPresentacion = UUID.randomUUID();
        Page<Object[]> filas = new PageImpl<>(List.<Object[]>of(filaCon(idPresentacion)), PageRequest.of(0, 20), 1);
        when(presentacionRepository.buscarFilasPrecios(any(), any(), any())).thenReturn(filas);

        PrecioQueso vigente = new PrecioQueso();
        vigente.setPresentacion(presentacionCon(idPresentacion));
        vigente.setModalidad("PUBLICO");
        vigente.setPrecio(BigDecimal.valueOf(12.00));
        vigente.setVigenteDesde(LocalDate.now().minusDays(5));
        when(repository.findByPresentacionIdIn(List.of(idPresentacion))).thenReturn(List.of(vigente));

        var resultado = service.listarPaginado(null, null, PageRequest.of(0, 20));

        assertThat(resultado.totalElementos()).isEqualTo(1);
        FilaPrecioPresentacionResponse fila = resultado.contenido().get(0);
        assertThat(fila.publico().estado()).isEqualTo("VIGENTE");
        assertThat(fila.publico().precio()).isEqualByComparingTo("12.00");
        assertThat(fila.mayorista().estado()).isEqualTo("SIN_PRECIO");
        assertThat(fila.proveedor().estado()).isEqualTo("SIN_PRECIO");
    }

    @Test
    void listarPaginadoUnaFilaConPresentacionNulaNoConsultaPreciosYQuedaSinPrecioEnLasTresModalidades() {
        Page<Object[]> filas = new PageImpl<>(List.<Object[]>of(filaCon(null)), PageRequest.of(0, 20), 1);
        when(presentacionRepository.buscarFilasPrecios(any(), any(), any())).thenReturn(filas);

        var resultado = service.listarPaginado(null, null, PageRequest.of(0, 20));

        FilaPrecioPresentacionResponse fila = resultado.contenido().get(0);
        assertThat(fila.presentacionId()).isNull();
        assertThat(fila.mayorista().estado()).isEqualTo("SIN_PRECIO");
        assertThat(fila.proveedor().estado()).isEqualTo("SIN_PRECIO");
        assertThat(fila.publico().estado()).isEqualTo("SIN_PRECIO");
        verify(repository, never()).findByPresentacionIdIn(any());
    }

    @Test
    void listarPaginadoDistingueProgramadoDeVigenteUsandoLaFechaDeHoy() {
        UUID idPresentacion = UUID.randomUUID();
        Page<Object[]> filas = new PageImpl<>(List.<Object[]>of(filaCon(idPresentacion)), PageRequest.of(0, 20), 1);
        when(presentacionRepository.buscarFilasPrecios(any(), any(), any())).thenReturn(filas);

        PrecioQueso programado = new PrecioQueso();
        programado.setPresentacion(presentacionCon(idPresentacion));
        programado.setModalidad("MAYORISTA");
        programado.setPrecio(BigDecimal.valueOf(6.00));
        programado.setVigenteDesde(LocalDate.now().plusDays(15));
        when(repository.findByPresentacionIdIn(List.of(idPresentacion))).thenReturn(List.of(programado));

        var resultado = service.listarPaginado(null, null, PageRequest.of(0, 20));

        FilaPrecioPresentacionResponse fila = resultado.contenido().get(0);
        assertThat(fila.mayorista().estado()).isEqualTo("PROGRAMADO");
        assertThat(fila.mayorista().precio()).isEqualByComparingTo("6.00");
        assertThat(fila.mayorista().vigenteDesde()).isEqualTo(LocalDate.now().plusDays(15));
    }

    @Test
    void listarPaginadoPropagaFamiliaIdYBusquedaAlRepositorioSinModificarlos() {
        Page<Object[]> vacia = new PageImpl<>(List.of(), PageRequest.of(0, 20), 0);
        when(presentacionRepository.buscarFilasPrecios(familiaId, "%botella%", PageRequest.of(0, 20))).thenReturn(vacia);

        service.listarPaginado(familiaId, "botella", PageRequest.of(0, 20));

        verify(presentacionRepository).buscarFilasPrecios(familiaId, "%botella%", PageRequest.of(0, 20));
    }

    @Test
    void listarPaginadoPliegaLaBusquedaSinAcentosNiMayusculas() {
        Page<Object[]> vacia = new PageImpl<>(List.of(), PageRequest.of(0, 20), 0);
        when(presentacionRepository.buscarFilasPrecios(familiaId, "%botellon%", PageRequest.of(0, 20))).thenReturn(vacia);

        service.listarPaginado(familiaId, "Botellón", PageRequest.of(0, 20));

        verify(presentacionRepository).buscarFilasPrecios(familiaId, "%botellon%", PageRequest.of(0, 20));
    }
}
