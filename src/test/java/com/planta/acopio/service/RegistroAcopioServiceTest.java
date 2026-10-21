package com.planta.acopio.service;

import com.planta.acopio.dto.RegistroAcopioDTO;
import com.planta.acopio.entity.*;
import com.planta.acopio.exception.RecursoInactivoException;
import com.planta.acopio.exception.RecursoNoEncontradoException;
import com.planta.acopio.exception.ValidacionNegocioException;
import com.planta.acopio.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

// CORE_DESIGN.md §16, prioridad 1: idempotencia por uuid_cliente es la regla
// de negocio mas critica del diseno offline-first -- se prueba primero.
@ExtendWith(MockitoExtension.class)
class RegistroAcopioServiceTest {

    @Mock private RegistroAcopioRepository repository;
    @Mock private ProveedorRepository proveedorRepository;
    @Mock private UnidadRepository unidadRepository;
    @Mock private UsuarioRepository usuarioRepository;
    @Mock private MotivoObservacionRepository motivoObservacionRepository;
    @Mock private AnomaliaService anomaliaService;
    @Mock private LoteProduccionRepository loteProduccionRepository;
    @Mock private JornadaRepository jornadaRepository;

    private RegistroAcopioService service;

    private final UUID proveedorId = UUID.randomUUID();
    private final UUID unidadId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new RegistroAcopioService(repository, proveedorRepository, unidadRepository,
                usuarioRepository, motivoObservacionRepository, anomaliaService, loteProduccionRepository,
                jornadaRepository, 30, 5);

        // Simula un usuario autenticado (JWT) para las pruebas que llegan hasta usuarioActual()
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("acopiador@planta.com", null));
    }

    @Test
    void siUuidClienteYaExisteDevuelveElExistenteSinCrearDuplicado() {
        String uuidCliente = UUID.randomUUID().toString();
        RegistroAcopio existente = registroValido(uuidCliente);
        when(repository.findByUuidCliente(uuidCliente)).thenReturn(Optional.of(existente));

        var dto = dtoValido(uuidCliente);
        var respuesta = service.registrarOIgnorarSiDuplicado(dto);

        assertThat(respuesta.uuidCliente()).isEqualTo(uuidCliente);
        // Nunca se debe llegar a crear un proveedor/unidad nuevos ni guardar de nuevo.
        verify(repository, never()).save(any());
        verify(proveedorRepository, never()).findById(any());
    }

    @Test
    void siNoExisteCreaElRegistroYEvaluaAnomalia() {
        String uuidCliente = UUID.randomUUID().toString();
        when(repository.findByUuidCliente(uuidCliente)).thenReturn(Optional.empty());

        Proveedor proveedor = new Proveedor();
        proveedor.setId(proveedorId);
        proveedor.setNombre("Proveedor Test");
        proveedor.setActivo(true);
        when(proveedorRepository.findById(proveedorId)).thenReturn(Optional.of(proveedor));

        Usuario acopiador = new Usuario();
        acopiador.setId(UUID.randomUUID());
        acopiador.setEmail("acopiador@planta.com");
        when(usuarioRepository.findByEmail("acopiador@planta.com")).thenReturn(Optional.of(acopiador));

        Unidad unidad = new Unidad();
        unidad.setId(unidadId);
        unidad.setResponsable(acopiador);
        when(unidadRepository.findById(unidadId)).thenReturn(Optional.of(unidad));

        when(repository.save(any(RegistroAcopio.class))).thenAnswer(inv -> {
            RegistroAcopio r = inv.getArgument(0);
            r.setId(UUID.randomUUID());
            return r;
        });

        var dto = dtoValido(uuidCliente);
        var respuesta = service.registrarOIgnorarSiDuplicado(dto);

        assertThat(respuesta.uuidCliente()).isEqualTo(uuidCliente);
        verify(repository).save(any(RegistroAcopio.class));
        verify(anomaliaService).evaluarNuevoRegistro(any(RegistroAcopio.class)); // RF-INN-03
    }

    // Jornada "Acopio multi-zona" (frontend móvil): verifica con evidencia, no solo lectura de código, que
    // el backend NO ata un registro de acopio a que el proveedor y la unidad compartan zona -- un acopiador
    // puede recorrer proveedores de cualquier zona con la unidad que tenga asignada. Si algún día se agrega
    // una restricción de zona acá, este test la va a hacer fallar a propósito.
    @Test
    void permiteRegistrarUnaEntregaDeUnProveedorDeOtraZonaQueLaDeLaUnidad() {
        String uuidCliente = UUID.randomUUID().toString();
        when(repository.findByUuidCliente(uuidCliente)).thenReturn(Optional.empty());

        Zona zonaDelProveedor = new Zona();
        zonaDelProveedor.setId(UUID.randomUUID());
        zonaDelProveedor.setNombre("Zona Norte");
        Proveedor proveedor = new Proveedor();
        proveedor.setId(proveedorId);
        proveedor.setNombre("Proveedor Test");
        proveedor.setActivo(true);
        proveedor.setZonaActual(zonaDelProveedor);
        when(proveedorRepository.findById(proveedorId)).thenReturn(Optional.of(proveedor));

        Zona zonaDeLaUnidad = new Zona();
        zonaDeLaUnidad.setId(UUID.randomUUID()); // deliberadamente distinta de zonaDelProveedor
        zonaDeLaUnidad.setNombre("Zona Sur");
        Usuario acopiador = new Usuario();
        acopiador.setId(UUID.randomUUID());
        acopiador.setEmail("acopiador@planta.com");
        when(usuarioRepository.findByEmail("acopiador@planta.com")).thenReturn(Optional.of(acopiador));

        Unidad unidad = new Unidad();
        unidad.setId(unidadId);
        unidad.setZona(zonaDeLaUnidad);
        unidad.setResponsable(acopiador);
        when(unidadRepository.findById(unidadId)).thenReturn(Optional.of(unidad));

        when(repository.save(any(RegistroAcopio.class))).thenAnswer(inv -> {
            RegistroAcopio r = inv.getArgument(0);
            r.setId(UUID.randomUUID());
            return r;
        });

        var dto = dtoValido(uuidCliente);
        var respuesta = service.registrarOIgnorarSiDuplicado(dto);

        assertThat(respuesta.uuidCliente()).isEqualTo(uuidCliente);
        verify(repository).save(any(RegistroAcopio.class));
    }

    // Fase 1 de jornada (docs/ESPEC_JORNADA_TRAZABILIDAD.md, Ecolacteos-Frontend): asociacion opcional de
    // un RegistroAcopio con la jornada activa, ya resuelta a id de servidor por el motor de sync del movil.
    @Test
    void asociaLaJornadaCuandoJornadaIdViajaEnElDto() {
        String uuidCliente = UUID.randomUUID().toString();
        when(repository.findByUuidCliente(uuidCliente)).thenReturn(Optional.empty());

        Proveedor proveedor = new Proveedor();
        proveedor.setId(proveedorId);
        proveedor.setActivo(true);
        when(proveedorRepository.findById(proveedorId)).thenReturn(Optional.of(proveedor));

        Usuario acopiador = new Usuario();
        acopiador.setId(UUID.randomUUID());
        acopiador.setEmail("acopiador@planta.com");
        when(usuarioRepository.findByEmail("acopiador@planta.com")).thenReturn(Optional.of(acopiador));

        Unidad unidad = new Unidad();
        unidad.setId(unidadId);
        unidad.setResponsable(acopiador);
        when(unidadRepository.findById(unidadId)).thenReturn(Optional.of(unidad));

        UUID jornadaId = UUID.randomUUID();
        Jornada jornada = new Jornada();
        jornada.setId(jornadaId);
        jornada.setAcopiador(acopiador);
        when(jornadaRepository.findById(jornadaId)).thenReturn(Optional.of(jornada));

        when(repository.save(any(RegistroAcopio.class))).thenAnswer(inv -> {
            RegistroAcopio r = inv.getArgument(0);
            r.setId(UUID.randomUUID());
            return r;
        });

        service.registrarOIgnorarSiDuplicado(dtoConJornada(uuidCliente, jornadaId));

        var captor = org.mockito.ArgumentCaptor.forClass(RegistroAcopio.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getJornada()).isEqualTo(jornada);
    }

    @Test
    void rechazaJornadaInexistente() {
        String uuidCliente = UUID.randomUUID().toString();
        when(repository.findByUuidCliente(uuidCliente)).thenReturn(Optional.empty());
        Proveedor proveedor = new Proveedor();
        proveedor.setId(proveedorId);
        proveedor.setActivo(true);
        when(proveedorRepository.findById(proveedorId)).thenReturn(Optional.of(proveedor));
        Usuario acopiador = new Usuario();
        acopiador.setId(UUID.randomUUID());
        acopiador.setEmail("acopiador@planta.com");
        when(usuarioRepository.findByEmail("acopiador@planta.com")).thenReturn(Optional.of(acopiador));
        Unidad unidad = new Unidad();
        unidad.setId(unidadId);
        unidad.setResponsable(acopiador);
        when(unidadRepository.findById(unidadId)).thenReturn(Optional.of(unidad));
        UUID jornadaId = UUID.randomUUID();
        when(jornadaRepository.findById(jornadaId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.registrarOIgnorarSiDuplicado(dtoConJornada(uuidCliente, jornadaId)))
                .isInstanceOf(RecursoNoEncontradoException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void rechazaJornadaDeOtroAcopiador() {
        String uuidCliente = UUID.randomUUID().toString();
        when(repository.findByUuidCliente(uuidCliente)).thenReturn(Optional.empty());
        Proveedor proveedor = new Proveedor();
        proveedor.setId(proveedorId);
        proveedor.setActivo(true);
        when(proveedorRepository.findById(proveedorId)).thenReturn(Optional.of(proveedor));
        Usuario acopiador = new Usuario();
        acopiador.setId(UUID.randomUUID());
        acopiador.setEmail("acopiador@planta.com");
        when(usuarioRepository.findByEmail("acopiador@planta.com")).thenReturn(Optional.of(acopiador));
        Unidad unidad = new Unidad();
        unidad.setId(unidadId);
        unidad.setResponsable(acopiador);
        when(unidadRepository.findById(unidadId)).thenReturn(Optional.of(unidad));

        UUID jornadaId = UUID.randomUUID();
        Jornada jornadaAjena = new Jornada();
        jornadaAjena.setId(jornadaId);
        Usuario otro = new Usuario();
        otro.setId(UUID.randomUUID());
        jornadaAjena.setAcopiador(otro);
        when(jornadaRepository.findById(jornadaId)).thenReturn(Optional.of(jornadaAjena));

        assertThatThrownBy(() -> service.registrarOIgnorarSiDuplicado(dtoConJornada(uuidCliente, jornadaId)))
                .isInstanceOf(ValidacionNegocioException.class);
        verify(repository, never()).save(any());
    }

    private RegistroAcopioDTO dtoConJornada(String uuidCliente, UUID jornadaId) {
        return new RegistroAcopioDTO(uuidCliente, proveedorId, unidadId,
                LocalDateTime.now(), BigDecimal.valueOf(45.5), null, null, null, false, null, jornadaId);
    }

    @Test
    void rechazaRegistrarConUnidadAsignadaAOtroAcopiador() {
        String uuidCliente = UUID.randomUUID().toString();
        when(repository.findByUuidCliente(uuidCliente)).thenReturn(Optional.empty());

        Proveedor proveedor = new Proveedor();
        proveedor.setId(proveedorId);
        proveedor.setNombre("Proveedor Test");
        proveedor.setActivo(true);
        when(proveedorRepository.findById(proveedorId)).thenReturn(Optional.of(proveedor));

        Usuario acopiador = new Usuario();
        acopiador.setId(UUID.randomUUID());
        acopiador.setEmail("acopiador@planta.com");
        when(usuarioRepository.findByEmail("acopiador@planta.com")).thenReturn(Optional.of(acopiador));

        Usuario otro = new Usuario();
        otro.setId(UUID.randomUUID());
        Unidad unidadAjena = new Unidad();
        unidadAjena.setId(unidadId);
        unidadAjena.setPlaca("AJENA-01");
        unidadAjena.setResponsable(otro);
        when(unidadRepository.findById(unidadId)).thenReturn(Optional.of(unidadAjena));

        assertThatThrownBy(() -> service.registrarOIgnorarSiDuplicado(dtoValido(uuidCliente)))
                .isInstanceOf(ValidacionNegocioException.class)
                .hasMessageContaining("no esta asignada");
        verify(repository, never()).save(any());
    }

    @Test
    void rechazaProveedorInactivo() {
        String uuidCliente = UUID.randomUUID().toString();
        when(repository.findByUuidCliente(uuidCliente)).thenReturn(Optional.empty());

        Proveedor proveedor = new Proveedor();
        proveedor.setId(proveedorId);
        proveedor.setActivo(false); // inactivo
        when(proveedorRepository.findById(proveedorId)).thenReturn(Optional.of(proveedor));

        var dto = dtoValido(uuidCliente);

        assertThatThrownBy(() -> service.registrarOIgnorarSiDuplicado(dto))
                .isInstanceOf(RecursoInactivoException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void rechazaFechaHoraFueraDeToleranciaHaciaAtras() {
        String uuidCliente = UUID.randomUUID().toString();
        when(repository.findByUuidCliente(uuidCliente)).thenReturn(Optional.empty());

        var dto = new RegistroAcopioDTO(uuidCliente, proveedorId, unidadId,
                LocalDateTime.now().minusDays(31), // 1 dia mas de lo tolerado (-30)
                BigDecimal.valueOf(45), null, null, null, false, null, null);

        assertThatThrownBy(() -> service.registrarOIgnorarSiDuplicado(dto))
                .isInstanceOf(ValidacionNegocioException.class);
    }

    @Test
    void rechazaProveedorInexistente() {
        String uuidCliente = UUID.randomUUID().toString();
        when(repository.findByUuidCliente(uuidCliente)).thenReturn(Optional.empty());
        when(proveedorRepository.findById(proveedorId)).thenReturn(Optional.empty());

        var dto = dtoValido(uuidCliente);

        assertThatThrownBy(() -> service.registrarOIgnorarSiDuplicado(dto))
                .isInstanceOf(RecursoNoEncontradoException.class);
    }

    // Bloque 2 (confirmado): bandeja operativa con filtros combinables.
    @Test
    void buscarSinFiltrosDevuelveTodosLosRegistros() {
        when(repository.buscarConFiltros(isNull(), isNull(), isNull(), eq(false)))
                .thenReturn(List.of(registroValido(UUID.randomUUID().toString()), registroValido(UUID.randomUUID().toString())));
        when(loteProduccionRepository.idsYaVinculados(anyList())).thenReturn(List.of());

        var resultado = service.buscar(null, null, null);

        assertThat(resultado).hasSize(2);
    }

    // Fase 4 PRODUCCION (docs/PROMPTS_FINALIZAR_MOVIL.md): "disena e implementa
    // una forma verificable de distinguir los elegibles antes del POST".
    @Test
    void buscarMarcaDisponibleParaLoteEnFalseSoloParaLosYaVinculados() {
        RegistroAcopio libre = registroValido(UUID.randomUUID().toString());
        RegistroAcopio vinculado = registroValido(UUID.randomUUID().toString());
        when(repository.buscarConFiltros(isNull(), isNull(), isNull(), eq(false)))
                .thenReturn(List.of(libre, vinculado));
        when(loteProduccionRepository.idsYaVinculados(anyList())).thenReturn(List.of(vinculado.getId()));

        var resultado = service.buscar(null, null, null);

        var respuestaLibre = resultado.stream().filter(r -> r.id().equals(libre.getId())).findFirst().orElseThrow();
        var respuestaVinculada = resultado.stream().filter(r -> r.id().equals(vinculado.getId())).findFirst().orElseThrow();
        assertThat(respuestaLibre.disponibleParaLote()).isTrue();
        assertThat(respuestaVinculada.disponibleParaLote()).isFalse();
    }

    @Test
    void buscarNuncaConsultaVinculadosSiLaListaVieneVacia() {
        when(repository.buscarConFiltros(any(), any(), any(), anyBoolean())).thenReturn(List.of());

        service.buscar(LocalDate.now(), UUID.randomUUID(), true);

        // IN () con una coleccion vacia no es valido en JPQL -- nunca se debe llamar en este caso.
        verify(loteProduccionRepository, never()).idsYaVinculados(any());
    }

    @Test
    void obtenerMarcaDisponibleParaLoteSegunElRepositorioDeLotes() {
        RegistroAcopio registro = registroValido(UUID.randomUUID().toString());
        when(repository.findById(registro.getId())).thenReturn(Optional.of(registro));
        when(loteProduccionRepository.existeRegistroYaVinculadoAOtroLote(registro.getId())).thenReturn(true);

        var respuesta = service.obtener(registro.getId());

        assertThat(respuesta.disponibleParaLote()).isFalse();
    }

    @Test
    void historialPorProveedorMarcaDisponibleParaLoteEnFalseSoloParaLosYaVinculados() {
        RegistroAcopio libre = registroValido(UUID.randomUUID().toString());
        RegistroAcopio vinculado = registroValido(UUID.randomUUID().toString());
        when(repository.findByProveedorIdOrderByFechaHoraDesc(proveedorId)).thenReturn(List.of(libre, vinculado));
        when(loteProduccionRepository.idsYaVinculados(anyList())).thenReturn(List.of(vinculado.getId()));

        var resultado = service.historialPorProveedor(proveedorId);

        var respuestaLibre = resultado.stream().filter(r -> r.id().equals(libre.getId())).findFirst().orElseThrow();
        var respuestaVinculada = resultado.stream().filter(r -> r.id().equals(vinculado.getId())).findFirst().orElseThrow();
        assertThat(respuestaLibre.disponibleParaLote()).isTrue();
        assertThat(respuestaVinculada.disponibleParaLote()).isFalse();
    }

    @Test
    void buscarPorFechaConvierteALaVentanaDelDiaCalendarioCompleto() {
        LocalDate fecha = LocalDate.of(2026, 9, 10);
        when(repository.buscarConFiltros(eq(fecha.atStartOfDay()), eq(fecha.plusDays(1).atStartOfDay()), isNull(), eq(false)))
                .thenReturn(List.of());

        service.buscar(fecha, null, null);

        verify(repository).buscarConFiltros(fecha.atStartOfDay(), fecha.plusDays(1).atStartOfDay(), null, false);
    }

    @Test
    void buscarPorZonaPasaElZonaIdTalCual() {
        UUID zonaId = UUID.randomUUID();
        when(repository.buscarConFiltros(isNull(), isNull(), eq(zonaId), eq(false))).thenReturn(List.of());

        service.buscar(null, zonaId, null);

        verify(repository).buscarConFiltros(null, null, zonaId, false);
    }

    @Test
    void buscarConSinAnalisisTrueLoPropagaAlRepositorio() {
        when(repository.buscarConFiltros(isNull(), isNull(), isNull(), eq(true))).thenReturn(List.of());

        service.buscar(null, null, true);

        verify(repository).buscarConFiltros(null, null, null, true);
    }

    @Test
    void buscarCombinaFechaZonaYSinAnalisisALaVez() {
        LocalDate fecha = LocalDate.of(2026, 9, 10);
        UUID zonaId = UUID.randomUUID();
        when(repository.buscarConFiltros(any(), any(), eq(zonaId), eq(true))).thenReturn(List.of());

        service.buscar(fecha, zonaId, true);

        verify(repository).buscarConFiltros(fecha.atStartOfDay(), fecha.plusDays(1).atStartOfDay(), zonaId, true);
    }

    @Test
    void buscarDevuelveListaVaciaSiNoHayCoincidencias() {
        when(repository.buscarConFiltros(any(), any(), any(), anyBoolean())).thenReturn(List.of());

        var resultado = service.buscar(LocalDate.now(), UUID.randomUUID(), true);

        assertThat(resultado).isEmpty();
    }

    private RegistroAcopioDTO dtoValido(String uuidCliente) {
        return new RegistroAcopioDTO(uuidCliente, proveedorId, unidadId,
                LocalDateTime.now(), BigDecimal.valueOf(45.5), null, null, null, false, null, null);
    }

    // --- Paginacion pendiente (web) ---

    @Test
    void buscarPaginadoEnviaVentanaDelDiaYPatronDeBusquedaYCalculaDisponibilidadSoloDeLaPagina() {
        LocalDate fecha = LocalDate.of(2026, 9, 10);
        var pageable = org.springframework.data.domain.PageRequest.of(2, 10);
        RegistroAcopio libre = registroValido(UUID.randomUUID().toString());
        RegistroAcopio vinculado = registroValido(UUID.randomUUID().toString());
        when(repository.buscarConFiltrosPaginado(eq(fecha.atStartOfDay()), eq(fecha.plusDays(1).atStartOfDay()),
                isNull(), eq(true), eq("%abc-1!_%"), eq(pageable)))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(libre, vinculado), pageable, 42));
        when(loteProduccionRepository.idsYaVinculados(List.of(libre.getId(), vinculado.getId())))
                .thenReturn(List.of(vinculado.getId()));

        var pagina = service.buscarPaginado(fecha, null, true, " ABC-1_ ", pageable);

        assertThat(pagina.getTotalElements()).isEqualTo(42);
        assertThat(pagina.getNumber()).isEqualTo(2);
        assertThat(pagina.getContent()).extracting(r -> r.disponibleParaLote()).containsExactly(true, false);
    }

    @Test
    void buscarPaginadoSinFiltrosNoEnviaVentanaNiBusqueda() {
        var pageable = org.springframework.data.domain.PageRequest.of(0, 20);
        when(repository.buscarConFiltrosPaginado(isNull(), isNull(), isNull(), eq(false), isNull(), eq(pageable)))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(), pageable, 0));

        var pagina = service.buscarPaginado(null, null, null, "  ", pageable);

        assertThat(pagina.getContent()).isEmpty();
        verify(loteProduccionRepository, never()).idsYaVinculados(anyList());
    }

    @Test
    void agruparPorJornadaConvierteLasFilasNativasSinImportarElTipoDelDriver() {
        var pageable = org.springframework.data.domain.PageRequest.of(0, 20);
        UUID jornadaId = UUID.randomUUID();
        UUID primerId = UUID.randomUUID();
        UUID sueltoId = UUID.randomUUID();
        // Jornada: tipos "nativos" (UUID, LocalDateTime, Long, BigDecimal).
        Object[] jornada = {jornadaId, true, 3L, new BigDecimal("150.25"), LocalDateTime.of(2026, 9, 10, 6, 0),
                primerId, "ABC-123", "Ana"};
        // Registro suelto: tipos alternativos (String, Timestamp, BigInteger) que algunos drivers devuelven.
        Object[] suelto = {sueltoId.toString(), false, java.math.BigInteger.ONE, 40,
                java.sql.Timestamp.valueOf(LocalDateTime.of(2026, 9, 10, 5, 0)), sueltoId.toString(), "XYZ-9", null};
        when(repository.agruparPorJornadaPaginado(any(), any(), any(), anyBoolean(), any(), eq(pageable)))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(jornada, suelto), pageable, 2));

        var pagina = service.agruparPorJornada(LocalDate.of(2026, 9, 10), null, false, null, pageable);

        var primera = pagina.getContent().get(0);
        assertThat(primera.clave()).isEqualTo(jornadaId);
        assertThat(primera.esJornada()).isTrue();
        assertThat(primera.cantidad()).isEqualTo(3);
        assertThat(primera.totalLitros()).isEqualByComparingTo("150.25");
        assertThat(primera.acopiadorNombre()).isEqualTo("Ana");
        assertThat(primera.placa()).isEqualTo("ABC-123");
        assertThat(primera.primerAcopioId()).isEqualTo(primerId);
        var segunda = pagina.getContent().get(1);
        assertThat(segunda.clave()).isEqualTo(sueltoId);
        assertThat(segunda.esJornada()).isFalse();
        assertThat(segunda.cantidad()).isEqualTo(1);
        assertThat(segunda.totalLitros()).isEqualByComparingTo("40");
        assertThat(segunda.fecha()).isEqualTo(LocalDateTime.of(2026, 9, 10, 5, 0));
        assertThat(segunda.acopiadorNombre()).isNull();
        assertThat(pagina.getTotalElements()).isEqualTo(2);
    }

    @Test
    void totalesPorProveedorIgnoranLaBusquedaDeTexto() {
        LocalDate fecha = LocalDate.of(2026, 9, 10);
        UUID zonaId = UUID.randomUUID();
        when(repository.totalesPorProveedor(fecha.atStartOfDay(), fecha.plusDays(1).atStartOfDay(), zonaId, false, null))
                .thenReturn(List.<Object[]>of(new Object[]{proveedorId, "Juan", new BigDecimal("80.5"), 2L}));

        var totales = service.totalesPorProveedor(fecha, zonaId, null);

        assertThat(totales).hasSize(1);
        assertThat(totales.get(0).proveedorNombre()).isEqualTo("Juan");
        assertThat(totales.get(0).totalLitros()).isEqualByComparingTo("80.5");
        assertThat(totales.get(0).cantidad()).isEqualTo(2);
    }

    @Test
    void historialPorProveedorPaginadoConservaTotalYDisponibilidad() {
        var pageable = org.springframework.data.domain.PageRequest.of(1, 1);
        RegistroAcopio vinculado = registroValido(UUID.randomUUID().toString());
        when(repository.findByProveedorIdOrderByFechaHoraDescIdDesc(proveedorId, pageable))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(vinculado), pageable, 5));
        when(loteProduccionRepository.idsYaVinculados(List.of(vinculado.getId()))).thenReturn(List.of(vinculado.getId()));

        var pagina = service.historialPorProveedor(proveedorId, pageable);

        assertThat(pagina.getTotalElements()).isEqualTo(5);
        assertThat(pagina.getTotalPages()).isEqualTo(5);
        assertThat(pagina.getContent().get(0).disponibleParaLote()).isFalse();
    }

    private RegistroAcopio registroValido(String uuidCliente) {
        RegistroAcopio r = new RegistroAcopio();
        r.setId(UUID.randomUUID());
        r.setUuidCliente(uuidCliente);
        Proveedor p = new Proveedor();
        p.setId(proveedorId);
        p.setNombre("Proveedor Test");
        r.setProveedor(p);
        Unidad u = new Unidad();
        u.setId(unidadId);
        r.setUnidad(u);
        r.setFechaHora(LocalDateTime.now());
        r.setLitros(BigDecimal.valueOf(45.5));
        return r;
    }
}
