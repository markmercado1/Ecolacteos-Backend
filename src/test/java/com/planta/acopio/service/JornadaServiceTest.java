package com.planta.acopio.service;

import com.planta.acopio.dto.JornadaDTO;
import com.planta.acopio.dto.JornadaEstadoRequest;
import com.planta.acopio.entity.EstadoJornada;
import com.planta.acopio.entity.Jornada;
import com.planta.acopio.entity.Proveedor;
import com.planta.acopio.entity.RegistroAcopio;
import com.planta.acopio.entity.Unidad;
import com.planta.acopio.entity.Usuario;
import com.planta.acopio.exception.ConflictoNegocioException;
import com.planta.acopio.exception.RecursoNoEncontradoException;
import com.planta.acopio.exception.ValidacionNegocioException;
import com.planta.acopio.repository.JornadaRepository;
import com.planta.acopio.repository.RegistroAcopioRepository;
import com.planta.acopio.repository.UnidadRepository;
import com.planta.acopio.repository.UsuarioRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

// Fase 1 -- Nucleo de jornada (docs/ESPEC_JORNADA_TRAZABILIDAD.md, Ecolacteos-Frontend). Mismo criterio de
// prioridad que RegistroAcopioServiceTest: idempotencia primero, despues las reglas de una sola jornada
// activa por acopiador/unidad y de cancelacion solo sin acopios -- son las reglas que el backend debe
// validar aunque el movil ya las respete (CLAUDE.md: "no confiar solo en Compose para integridad").
@ExtendWith(MockitoExtension.class)
class JornadaServiceTest {

    @Mock private JornadaRepository jornadaRepository;
    @Mock private UnidadRepository unidadRepository;
    @Mock private UsuarioRepository usuarioRepository;
    @Mock private RegistroAcopioRepository registroAcopioRepository;

    private JornadaService service;

    private final UUID unidadId = UUID.randomUUID();
    private final UUID acopiadorId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new JornadaService(jornadaRepository, unidadRepository, usuarioRepository, registroAcopioRepository);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("acopiador@planta.com", null));
        Usuario acopiador = new Usuario();
        acopiador.setId(acopiadorId);
        // lenient: algunas pruebas (idempotencia, jornada inexistente) devuelven antes de llegar a
        // usuarioActual() y nunca consultan este stub -- mismo criterio que otros ServiceTest del proyecto.
        lenient().when(usuarioRepository.findByEmail("acopiador@planta.com")).thenReturn(Optional.of(acopiador));
    }

    // Stub minimo para el mensaje de ConflictoNegocioException, que lee horaInicio/estado de la jornada
    // activa encontrada (ver JornadaService.verificarSinJornadaActivaConflictiva).
    private Jornada jornadaActivaExistente() {
        Jornada j = new Jornada();
        j.setId(UUID.randomUUID());
        j.setEstado(EstadoJornada.EN_RECOLECCION);
        j.setHoraInicio(LocalDateTime.now().minusHours(1));
        return j;
    }

    private Unidad unidadPropia() {
        Unidad unidad = new Unidad();
        unidad.setId(unidadId);
        unidad.setPlaca("ABC-123");
        Usuario responsable = new Usuario();
        responsable.setId(acopiadorId);
        unidad.setResponsable(responsable);
        return unidad;
    }

    private JornadaDTO dtoValido(String uuidCliente) {
        return new JornadaDTO(uuidCliente, unidadId, LocalDateTime.now(), BigDecimal.valueOf(-6.7), BigDecimal.valueOf(-78.5),
                null, null, null, null, null);
    }

    @Test
    void siUuidClienteYaExisteDevuelveElExistenteSinCrearDuplicado() {
        String uuidCliente = UUID.randomUUID().toString();
        Jornada existente = new Jornada();
        existente.setId(UUID.randomUUID());
        existente.setUuidCliente(uuidCliente);
        existente.setUnidad(unidadPropia());
        existente.setEstado(EstadoJornada.EN_RECOLECCION);
        existente.setHoraInicio(LocalDateTime.now());
        when(jornadaRepository.findByUuidCliente(uuidCliente)).thenReturn(Optional.of(existente));

        var respuesta = service.crear(dtoValido(uuidCliente));

        assertThat(respuesta.uuidCliente()).isEqualTo(uuidCliente);
        verify(jornadaRepository, never()).save(any());
        // Reintento: no vuelve a validar unidad ni jornada activa -- devuelve exactamente lo que ya existia.
        verifyNoInteractions(unidadRepository);
    }

    @Test
    void rechazaUnidadNoAsignadaAlAcopiador() {
        String uuidCliente = UUID.randomUUID().toString();
        when(jornadaRepository.findByUuidCliente(uuidCliente)).thenReturn(Optional.empty());
        Unidad ajena = new Unidad();
        ajena.setId(unidadId);
        ajena.setPlaca("XYZ-999");
        Usuario otro = new Usuario();
        otro.setId(UUID.randomUUID());
        ajena.setResponsable(otro);
        when(unidadRepository.findById(unidadId)).thenReturn(Optional.of(ajena));

        assertThatThrownBy(() -> service.crear(dtoValido(uuidCliente)))
                .isInstanceOf(ValidacionNegocioException.class);
        verify(jornadaRepository, never()).save(any());
    }

    @Test
    void rechazaUnidadInexistente() {
        String uuidCliente = UUID.randomUUID().toString();
        when(jornadaRepository.findByUuidCliente(uuidCliente)).thenReturn(Optional.empty());
        when(unidadRepository.findById(unidadId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.crear(dtoValido(uuidCliente)))
                .isInstanceOf(RecursoNoEncontradoException.class);
    }

    @Test
    void rechazaSegundaJornadaActivaParaElMismoAcopiador() {
        String uuidCliente = UUID.randomUUID().toString();
        when(jornadaRepository.findByUuidCliente(uuidCliente)).thenReturn(Optional.empty());
        when(unidadRepository.findById(unidadId)).thenReturn(Optional.of(unidadPropia()));
        when(jornadaRepository.findActivaPorAcopiador(acopiadorId)).thenReturn(Optional.of(jornadaActivaExistente()));

        assertThatThrownBy(() -> service.crear(dtoValido(uuidCliente)))
                .isInstanceOf(ConflictoNegocioException.class);
        verify(jornadaRepository, never()).save(any());
    }

    @Test
    void rechazaSegundaJornadaActivaParaLaMismaUnidad() {
        String uuidCliente = UUID.randomUUID().toString();
        when(jornadaRepository.findByUuidCliente(uuidCliente)).thenReturn(Optional.empty());
        when(unidadRepository.findById(unidadId)).thenReturn(Optional.of(unidadPropia()));
        when(jornadaRepository.findActivaPorAcopiador(acopiadorId)).thenReturn(Optional.empty());
        when(jornadaRepository.existeActivaParaUnidad(unidadId)).thenReturn(true);

        assertThatThrownBy(() -> service.crear(dtoValido(uuidCliente)))
                .isInstanceOf(ConflictoNegocioException.class);
    }

    @Test
    void unViolacionDeIndiceUnicoEnLaCarreraSeTraduceAConflicto() {
        String uuidCliente = UUID.randomUUID().toString();
        when(jornadaRepository.findByUuidCliente(uuidCliente)).thenReturn(Optional.empty());
        when(unidadRepository.findById(unidadId)).thenReturn(Optional.of(unidadPropia()));
        when(jornadaRepository.findActivaPorAcopiador(acopiadorId)).thenReturn(Optional.empty());
        when(jornadaRepository.existeActivaParaUnidad(unidadId)).thenReturn(false);
        when(jornadaRepository.save(any())).thenThrow(new DataIntegrityViolationException("indice unico violado"));

        assertThatThrownBy(() -> service.crear(dtoValido(uuidCliente)))
                .isInstanceOf(ConflictoNegocioException.class);
    }

    @Test
    void rechazaCrearDirectamenteEnEstadoRecibida() {
        String uuidCliente = UUID.randomUUID().toString();
        when(jornadaRepository.findByUuidCliente(uuidCliente)).thenReturn(Optional.empty());
        when(unidadRepository.findById(unidadId)).thenReturn(Optional.of(unidadPropia()));
        var dto = new JornadaDTO(uuidCliente, unidadId, LocalDateTime.now(), null, null,
                EstadoJornada.RECIBIDA, LocalDateTime.now(), null, null, null);

        assertThatThrownBy(() -> service.crear(dto))
                .isInstanceOf(ValidacionNegocioException.class);
    }

    // Recorrido offline completo: inicio, acopios y fin ocurrieron sin conexion -- se sincroniza recien al
    // reconectar y la jornada se crea DIRECTAMENTE en REGRESANDO_A_PLANTA, en una sola llamada.
    @Test
    void creaDirectamenteEnRegresandoAPlantaCuandoElRecorridoYaTermino() {
        String uuidCliente = UUID.randomUUID().toString();
        when(jornadaRepository.findByUuidCliente(uuidCliente)).thenReturn(Optional.empty());
        when(unidadRepository.findById(unidadId)).thenReturn(Optional.of(unidadPropia()));
        when(jornadaRepository.findActivaPorAcopiador(acopiadorId)).thenReturn(Optional.empty());
        when(jornadaRepository.existeActivaParaUnidad(unidadId)).thenReturn(false);
        when(jornadaRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var dto = new JornadaDTO(uuidCliente, unidadId, LocalDateTime.now().minusHours(2), null, null,
                EstadoJornada.REGRESANDO_A_PLANTA, LocalDateTime.now(), BigDecimal.valueOf(-6.8), BigDecimal.valueOf(-78.4), null);

        var respuesta = service.crear(dto);

        assertThat(respuesta.estado()).isEqualTo(EstadoJornada.REGRESANDO_A_PLANTA);
        assertThat(respuesta.horaFin()).isNotNull();
    }

    // Una jornada creada directamente CANCELADA (recorrido cancelado offline antes de sincronizar) nunca
    // choca con "jornada activa" -- nunca desplazo a otra ni corre ese riesgo.
    @Test
    void creaDirectamenteEnCanceladaSinVerificarConflictoDeActiva() {
        String uuidCliente = UUID.randomUUID().toString();
        when(jornadaRepository.findByUuidCliente(uuidCliente)).thenReturn(Optional.empty());
        when(unidadRepository.findById(unidadId)).thenReturn(Optional.of(unidadPropia()));
        when(jornadaRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var dto = new JornadaDTO(uuidCliente, unidadId, LocalDateTime.now().minusMinutes(5), null, null,
                EstadoJornada.CANCELADA, LocalDateTime.now(), null, null, "Error al iniciar");

        var respuesta = service.crear(dto);

        assertThat(respuesta.estado()).isEqualTo(EstadoJornada.CANCELADA);
        verify(jornadaRepository, never()).findActivaPorAcopiador(any());
        verify(jornadaRepository, never()).existeActivaParaUnidad(any());
    }

    private Jornada jornadaEnRecoleccion(String uuidCliente) {
        Jornada j = new Jornada();
        j.setId(UUID.randomUUID());
        j.setUuidCliente(uuidCliente);
        Usuario acopiador = new Usuario();
        acopiador.setId(acopiadorId);
        j.setAcopiador(acopiador);
        j.setUnidad(unidadPropia());
        j.setEstado(EstadoJornada.EN_RECOLECCION);
        j.setHoraInicio(LocalDateTime.now().minusHours(1));
        return j;
    }

    @Test
    void finalizarCambiaAregresandoAPlanta() {
        String uuidCliente = UUID.randomUUID().toString();
        Jornada j = jornadaEnRecoleccion(uuidCliente);
        when(jornadaRepository.findByUuidCliente(uuidCliente)).thenReturn(Optional.of(j));
        when(jornadaRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var dto = new JornadaEstadoRequest(uuidCliente, JornadaEstadoRequest.TipoTransicionJornada.FINALIZAR,
                LocalDateTime.now(), BigDecimal.valueOf(-6.9), BigDecimal.valueOf(-78.3), null);

        var respuesta = service.aplicarTransicion(dto);

        assertThat(respuesta.estado()).isEqualTo(EstadoJornada.REGRESANDO_A_PLANTA);
        assertThat(respuesta.horaFin()).isNotNull();
    }

    @Test
    void finalizarEsIdempotenteSiYaEstaRegresandoAPlanta() {
        String uuidCliente = UUID.randomUUID().toString();
        Jornada j = jornadaEnRecoleccion(uuidCliente);
        j.setEstado(EstadoJornada.REGRESANDO_A_PLANTA);
        j.setHoraFin(LocalDateTime.now().minusMinutes(10));
        when(jornadaRepository.findByUuidCliente(uuidCliente)).thenReturn(Optional.of(j));

        var dto = new JornadaEstadoRequest(uuidCliente, JornadaEstadoRequest.TipoTransicionJornada.FINALIZAR,
                LocalDateTime.now(), null, null, null);

        var respuesta = service.aplicarTransicion(dto);

        assertThat(respuesta.estado()).isEqualTo(EstadoJornada.REGRESANDO_A_PLANTA);
        verify(jornadaRepository, never()).save(any());
    }

    @Test
    void rechazaFinalizarUnaJornadaCancelada() {
        String uuidCliente = UUID.randomUUID().toString();
        Jornada j = jornadaEnRecoleccion(uuidCliente);
        j.setEstado(EstadoJornada.CANCELADA);
        when(jornadaRepository.findByUuidCliente(uuidCliente)).thenReturn(Optional.of(j));

        var dto = new JornadaEstadoRequest(uuidCliente, JornadaEstadoRequest.TipoTransicionJornada.FINALIZAR,
                LocalDateTime.now(), null, null, null);

        assertThatThrownBy(() -> service.aplicarTransicion(dto)).isInstanceOf(ValidacionNegocioException.class);
    }

    @Test
    void cancelarSinAcopiosAsociadosCambiaACancelada() {
        String uuidCliente = UUID.randomUUID().toString();
        Jornada j = jornadaEnRecoleccion(uuidCliente);
        when(jornadaRepository.findByUuidCliente(uuidCliente)).thenReturn(Optional.of(j));
        when(registroAcopioRepository.countByJornadaId(j.getId())).thenReturn(0L);
        when(jornadaRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var dto = new JornadaEstadoRequest(uuidCliente, JornadaEstadoRequest.TipoTransicionJornada.CANCELAR,
                LocalDateTime.now(), null, null, "Inicio por error");

        var respuesta = service.aplicarTransicion(dto);

        assertThat(respuesta.estado()).isEqualTo(EstadoJornada.CANCELADA);
        assertThat(respuesta.motivoCancelacion()).isEqualTo("Inicio por error");
    }

    @Test
    void rechazaCancelarUnaJornadaConAcopiosAsociados() {
        String uuidCliente = UUID.randomUUID().toString();
        Jornada j = jornadaEnRecoleccion(uuidCliente);
        when(jornadaRepository.findByUuidCliente(uuidCliente)).thenReturn(Optional.of(j));
        when(registroAcopioRepository.countByJornadaId(j.getId())).thenReturn(2L);

        var dto = new JornadaEstadoRequest(uuidCliente, JornadaEstadoRequest.TipoTransicionJornada.CANCELAR,
                LocalDateTime.now(), null, null, "Inicio por error");

        assertThatThrownBy(() -> service.aplicarTransicion(dto)).isInstanceOf(ValidacionNegocioException.class);
        verify(jornadaRepository, never()).save(any());
    }

    @Test
    void cancelarEsIdempotenteSiYaEstaCancelada() {
        String uuidCliente = UUID.randomUUID().toString();
        Jornada j = jornadaEnRecoleccion(uuidCliente);
        j.setEstado(EstadoJornada.CANCELADA);
        when(jornadaRepository.findByUuidCliente(uuidCliente)).thenReturn(Optional.of(j));

        var dto = new JornadaEstadoRequest(uuidCliente, JornadaEstadoRequest.TipoTransicionJornada.CANCELAR,
                LocalDateTime.now(), null, null, "Motivo distinto en el reintento");

        var respuesta = service.aplicarTransicion(dto);

        assertThat(respuesta.estado()).isEqualTo(EstadoJornada.CANCELADA);
        verify(jornadaRepository, never()).save(any());
        verifyNoInteractions(registroAcopioRepository);
    }

    @Test
    void rechazaCancelarUnaJornadaYaRegresandoAPlanta() {
        String uuidCliente = UUID.randomUUID().toString();
        Jornada j = jornadaEnRecoleccion(uuidCliente);
        j.setEstado(EstadoJornada.REGRESANDO_A_PLANTA);
        when(jornadaRepository.findByUuidCliente(uuidCliente)).thenReturn(Optional.of(j));

        var dto = new JornadaEstadoRequest(uuidCliente, JornadaEstadoRequest.TipoTransicionJornada.CANCELAR,
                LocalDateTime.now(), null, null, null);

        assertThatThrownBy(() -> service.aplicarTransicion(dto)).isInstanceOf(ValidacionNegocioException.class);
    }

    // Aislamiento: una transicion no puede aplicarse a la jornada de OTRO acopiador (defensa en
    // profundidad -- ESPEC §19, "no confiar unicamente en el usuarioId enviado por el movil").
    @Test
    void rechazaTransicionSobreJornadaDeOtroAcopiador() {
        String uuidCliente = UUID.randomUUID().toString();
        Jornada j = jornadaEnRecoleccion(uuidCliente);
        Usuario otro = new Usuario();
        otro.setId(UUID.randomUUID());
        j.setAcopiador(otro);
        when(jornadaRepository.findByUuidCliente(uuidCliente)).thenReturn(Optional.of(j));

        var dto = new JornadaEstadoRequest(uuidCliente, JornadaEstadoRequest.TipoTransicionJornada.FINALIZAR,
                LocalDateTime.now(), null, null, null);

        assertThatThrownBy(() -> service.aplicarTransicion(dto)).isInstanceOf(ValidacionNegocioException.class);
        verify(jornadaRepository, never()).save(any());
    }

    @Test
    void rechazaTransicionSobreJornadaInexistente() {
        String uuidCliente = UUID.randomUUID().toString();
        when(jornadaRepository.findByUuidCliente(uuidCliente)).thenReturn(Optional.empty());

        var dto = new JornadaEstadoRequest(uuidCliente, JornadaEstadoRequest.TipoTransicionJornada.FINALIZAR,
                LocalDateTime.now(), null, null, null);

        assertThatThrownBy(() -> service.aplicarTransicion(dto)).isInstanceOf(RecursoNoEncontradoException.class);
    }

    // ---------------------------------------------------------------------------------------------
    // Fase 2 (docs/ESPEC_JORNADA_TRAZABILIDAD.md, Ecolacteos-Frontend): bandeja y detalle para RECEPCION.
    // ---------------------------------------------------------------------------------------------

    private RegistroAcopio acopioDe(UUID proveedorId, String proveedorNombre, BigDecimal litros) {
        RegistroAcopio r = new RegistroAcopio();
        r.setId(UUID.randomUUID());
        Proveedor proveedor = new Proveedor();
        proveedor.setId(proveedorId);
        proveedor.setNombre(proveedorNombre);
        r.setProveedor(proveedor);
        r.setLitros(litros);
        r.setFechaHora(LocalDateTime.now());
        return r;
    }

    @Test
    void listarPendientesRecepcionCalculaLitrosCantidadDeAcopiosYProveedoresDistintos() {
        Jornada j = jornadaEnRecoleccion(UUID.randomUUID().toString());
        j.setEstado(EstadoJornada.REGRESANDO_A_PLANTA);
        UUID proveedor1 = UUID.randomUUID();
        UUID proveedor2 = UUID.randomUUID();
        when(jornadaRepository.findByEstadoOrderByHoraInicioAsc(EstadoJornada.REGRESANDO_A_PLANTA))
                .thenReturn(List.of(j));
        when(registroAcopioRepository.findByJornadaIdOrderByFechaHoraAsc(j.getId())).thenReturn(List.of(
                acopioDe(proveedor1, "Aníbal Núñez", BigDecimal.valueOf(50)),
                acopioDe(proveedor2, "Alejandro Terrones", BigDecimal.valueOf(30)),
                acopioDe(proveedor1, "Aníbal Núñez", BigDecimal.valueOf(20))));

        var bandeja = service.listarPendientesRecepcion();

        assertThat(bandeja).hasSize(1);
        var item = bandeja.get(0);
        assertThat(item.id()).isEqualTo(j.getId());
        assertThat(item.unidadPlaca()).isEqualTo("ABC-123");
        assertThat(item.acopiadorNombre()).isNull(); // jornadaEnRecoleccion no fija nombre -- ver el test siguiente
        assertThat(item.cantidadAcopios()).isEqualTo(3);
        assertThat(item.cantidadProveedores()).isEqualTo(2);
        assertThat(item.litrosEsperados()).isEqualByComparingTo(BigDecimal.valueOf(100));
    }

    @Test
    void listarPendientesRecepcionSoloIncluyeJornadasRegresandoAPlanta() {
        when(jornadaRepository.findByEstadoOrderByHoraInicioAsc(EstadoJornada.REGRESANDO_A_PLANTA))
                .thenReturn(List.of());

        var bandeja = service.listarPendientesRecepcion();

        assertThat(bandeja).isEmpty();
        verify(jornadaRepository, never()).findByEstadoOrderByHoraInicioAsc(EstadoJornada.EN_RECOLECCION);
    }

    @Test
    void obtenerDetalleParaRecepcionIncluyeProveedoresVisitadosSinDuplicarYElTotalEsperado() {
        UUID jornadaId = UUID.randomUUID();
        Jornada j = jornadaEnRecoleccion(UUID.randomUUID().toString());
        j.setId(jornadaId);
        j.setEstado(EstadoJornada.REGRESANDO_A_PLANTA);
        UUID proveedor1 = UUID.randomUUID();
        UUID proveedor2 = UUID.randomUUID();
        when(jornadaRepository.findById(jornadaId)).thenReturn(Optional.of(j));
        when(registroAcopioRepository.findByJornadaIdOrderByFechaHoraAsc(jornadaId)).thenReturn(List.of(
                acopioDe(proveedor1, "Aníbal Núñez", BigDecimal.valueOf(50)),
                acopioDe(proveedor2, "Alejandro Terrones", BigDecimal.valueOf(30)),
                acopioDe(proveedor1, "Aníbal Núñez", BigDecimal.valueOf(20))));

        var detalle = service.obtenerDetalleParaRecepcion(jornadaId);

        assertThat(detalle.litrosEsperados()).isEqualByComparingTo(BigDecimal.valueOf(100));
        assertThat(detalle.proveedoresVisitados()).containsExactly("Aníbal Núñez", "Alejandro Terrones");
        assertThat(detalle.acopios()).hasSize(3);
    }

    @Test
    void obtenerDetalleParaRecepcionDeJornadaSinAcopiosDevuelveListaVaciaYTotalCero() {
        UUID jornadaId = UUID.randomUUID();
        Jornada j = jornadaEnRecoleccion(UUID.randomUUID().toString());
        j.setId(jornadaId);
        when(jornadaRepository.findById(jornadaId)).thenReturn(Optional.of(j));
        when(registroAcopioRepository.findByJornadaIdOrderByFechaHoraAsc(jornadaId)).thenReturn(List.of());

        var detalle = service.obtenerDetalleParaRecepcion(jornadaId);

        assertThat(detalle.litrosEsperados()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(detalle.proveedoresVisitados()).isEmpty();
        assertThat(detalle.acopios()).isEmpty();
    }

    @Test
    void rechazaDetalleDeJornadaInexistente() {
        UUID jornadaId = UUID.randomUUID();
        when(jornadaRepository.findById(jornadaId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.obtenerDetalleParaRecepcion(jornadaId))
                .isInstanceOf(RecursoNoEncontradoException.class);
    }
}
