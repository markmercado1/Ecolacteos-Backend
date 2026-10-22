package com.planta.acopio.service;

import com.planta.acopio.dto.RecepcionJornadaRequest;
import com.planta.acopio.dto.RecepcionPlantaRequest;
import com.planta.acopio.entity.EstadoConciliacion;
import com.planta.acopio.entity.EstadoJornada;
import com.planta.acopio.entity.Jornada;
import com.planta.acopio.entity.RecepcionPlanta;
import com.planta.acopio.entity.RegistroAcopio;
import com.planta.acopio.entity.Unidad;
import com.planta.acopio.entity.Usuario;
import com.planta.acopio.exception.ConflictoNegocioException;
import com.planta.acopio.exception.RecursoNoEncontradoException;
import com.planta.acopio.exception.ValidacionNegocioException;
import com.planta.acopio.repository.JornadaRepository;
import com.planta.acopio.repository.RecepcionPlantaRepository;
import com.planta.acopio.repository.RegistroAcopioRepository;
import com.planta.acopio.repository.UnidadRepository;
import com.planta.acopio.repository.UsuarioRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// CORE_DESIGN.md §16, prioridad 1 (regla de negocio critica): mejora #7 /
// hallazgo 3.6 -- litros_campo = 0 AND litros_planta > 0 debe forzar ALERTA
// sin importar lo que reporte diferencia_pct (que en ese caso la columna
// GENERATED de PostgreSQL devuelve 0 para evitar division por cero).
//
// Se invoca el metodo privado 'calcularEstado' via reflection porque es
// logica de calculo pura sin efectos de persistencia -- separarla en un
// metodo publico solo para testear rompe el encapsulamiento sin necesidad.
@ExtendWith(MockitoExtension.class)
class ConciliacionServiceTest {

    @Mock private RecepcionPlantaRepository repository;
    @Mock private UnidadRepository unidadRepository;
    @Mock private UsuarioRepository usuarioRepository;
    @Mock private ConfiguracionSistemaService configuracionSistemaService;
    @Mock private EntityManager entityManager;
    @Mock private RegistroAcopioRepository registroAcopioRepository;
    @Mock private JornadaRepository jornadaRepository;
    @Mock private InventarioLecheService inventarioLecheService;

    private ConciliacionService service;

    @BeforeEach
    void setUp() {
        service = new ConciliacionService(repository, unidadRepository, usuarioRepository,
                configuracionSistemaService, entityManager, registroAcopioRepository, jornadaRepository,
                inventarioLecheService);
    }

    @Test
    void litrosCampoCeroConLitrosPlantaPositivoForzaAlertaAunConDiferenciaCero() {
        // Caso borde exacto del hallazgo 3.6: diferencia_pct llega en 0 (columna
        // GENERATED evita division por cero), pero el escenario real es el mas
        // grave posible (0 litros digitados en campo, leche si llego a planta).
        EstadoConciliacion resultado = invocarCalcularEstado(
                BigDecimal.ZERO, BigDecimal.valueOf(120), BigDecimal.ZERO);

        assertThat(resultado).isEqualTo(EstadoConciliacion.ALERTA);
    }

    @Test
    void litrosCampoCeroConLitrosPlantaCeroNoEsAlerta() {
        // Caso legitimo: la unidad no opero ese dia/turno -- 0 y 0 no es una discrepancia.
        // No cae en el caso borde forzado (litros_planta no es > 0), por lo
        // que igual consulta el umbral configurado -- debe estubearse.
        when(configuracionSistemaService.valorDe("UMBRAL_CONCILIACION_PCT")).thenReturn("2.5");

        EstadoConciliacion resultado = invocarCalcularEstado(
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);

        assertThat(resultado).isEqualTo(EstadoConciliacion.OK);
    }

    @Test
    void diferenciaDentroDelUmbralEsOk() {
        when(configuracionSistemaService.valorDe("UMBRAL_CONCILIACION_PCT")).thenReturn("2.5");

        EstadoConciliacion resultado = invocarCalcularEstado(
                BigDecimal.valueOf(100), BigDecimal.valueOf(101), BigDecimal.valueOf(1.0));

        assertThat(resultado).isEqualTo(EstadoConciliacion.OK);
    }

    @Test
    void diferenciaSobreElUmbralEsAlerta() {
        when(configuracionSistemaService.valorDe("UMBRAL_CONCILIACION_PCT")).thenReturn("2.5");

        EstadoConciliacion resultado = invocarCalcularEstado(
                BigDecimal.valueOf(100), BigDecimal.valueOf(95), BigDecimal.valueOf(-5.0));

        assertThat(resultado).isEqualTo(EstadoConciliacion.ALERTA);
    }

    private EstadoConciliacion invocarCalcularEstado(BigDecimal litrosCampo, BigDecimal litrosPlanta, BigDecimal diferenciaPct) {
        return (EstadoConciliacion) ReflectionTestUtils.invokeMethod(
                service, "calcularEstado", litrosCampo, litrosPlanta, diferenciaPct);
    }

    // Fase 3, decision de negocio 3 (opcion B, confirmada): seleccion
    // explicita -- RECEPCION indica exactamente que RegistroAcopio componen
    // la recepcion (mismo patron ya probado en LoteProduccionServiceTest).
    @Test
    void vinculaExplicitamenteLosRegistroAcopioIndicados() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("recepcion@planta.com", null));

        UUID unidadId = UUID.randomUUID();
        UUID registroId1 = UUID.randomUUID();
        UUID registroId2 = UUID.randomUUID();

        when(repository.findByFechaAndUnidadIdAndTurno(any(), eq(unidadId), any())).thenReturn(Optional.empty());

        Unidad unidad = new Unidad();
        unidad.setId(unidadId);
        when(unidadRepository.findById(unidadId)).thenReturn(Optional.of(unidad));

        Usuario responsable = new Usuario();
        responsable.setId(UUID.randomUUID());
        when(usuarioRepository.findByEmail("recepcion@planta.com")).thenReturn(Optional.of(responsable));

        RegistroAcopio r1 = new RegistroAcopio();
        r1.setId(registroId1);
        RegistroAcopio r2 = new RegistroAcopio();
        r2.setId(registroId2);
        when(registroAcopioRepository.findById(registroId1)).thenReturn(Optional.of(r1));
        when(registroAcopioRepository.findById(registroId2)).thenReturn(Optional.of(r2));

        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(configuracionSistemaService.valorDe("UMBRAL_CONCILIACION_PCT")).thenReturn("2.5");
        when(registroAcopioRepository.sumLitrosPorUnidadYFecha(any(), any(), any())).thenReturn(BigDecimal.ZERO);

        var dto = new RecepcionPlantaRequest(LocalDate.now(), null, unidadId,
                BigDecimal.valueOf(100), BigDecimal.valueOf(100), List.of(registroId1, registroId2));

        var resultado = service.registrarYConciliar(dto);

        assertThat(resultado.registroAcopioIds()).containsExactlyInAnyOrder(registroId1, registroId2);
    }

    // Fase 1 del refactor de Produccion: litrosPlanta (autoritativo) debe alimentar la existencia de leche
    // en la MISMA operacion que registra la conciliacion manual.
    @Test
    void registrarYConciliarRegistraLaEntradaDeInventarioDeLecheConLitrosPlantaComoCantidad() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("recepcion@planta.com", null));
        UUID unidadId = UUID.randomUUID();
        when(repository.findByFechaAndUnidadIdAndTurno(any(), eq(unidadId), any())).thenReturn(Optional.empty());
        Unidad unidad = new Unidad();
        unidad.setId(unidadId);
        when(unidadRepository.findById(unidadId)).thenReturn(Optional.of(unidad));
        Usuario responsable = new Usuario();
        responsable.setId(UUID.randomUUID());
        when(usuarioRepository.findByEmail("recepcion@planta.com")).thenReturn(Optional.of(responsable));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(configuracionSistemaService.valorDe("UMBRAL_CONCILIACION_PCT")).thenReturn("2.5");
        when(registroAcopioRepository.sumLitrosPorUnidadYFecha(any(), any(), any())).thenReturn(BigDecimal.ZERO);

        var dto = new RecepcionPlantaRequest(LocalDate.now(), null, unidadId,
                BigDecimal.valueOf(315), BigDecimal.valueOf(300), List.of());

        service.registrarYConciliar(dto);

        ArgumentCaptor<RecepcionPlanta> captor = ArgumentCaptor.forClass(RecepcionPlanta.class);
        verify(inventarioLecheService).registrarEntradaRecepcion(captor.capture());
        // litrosPlanta (300), NUNCA litrosCampo (315): el saldo de leche solo lo alimenta lo autoritativo.
        assertThat(captor.getValue().getLitrosPlanta()).isEqualByComparingTo(BigDecimal.valueOf(300));
    }

    @Test
    void rechazaVincularUnRegistroAcopioQueNoExiste() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("recepcion@planta.com", null));

        UUID unidadId = UUID.randomUUID();
        UUID registroInexistente = UUID.randomUUID();

        when(repository.findByFechaAndUnidadIdAndTurno(any(), eq(unidadId), any())).thenReturn(Optional.empty());

        Unidad unidad = new Unidad();
        unidad.setId(unidadId);
        when(unidadRepository.findById(unidadId)).thenReturn(Optional.of(unidad));

        Usuario responsable = new Usuario();
        responsable.setId(UUID.randomUUID());
        when(usuarioRepository.findByEmail("recepcion@planta.com")).thenReturn(Optional.of(responsable));
        when(registroAcopioRepository.findById(registroInexistente)).thenReturn(Optional.empty());

        var dto = new RecepcionPlantaRequest(LocalDate.now(), null, unidadId,
                BigDecimal.valueOf(100), BigDecimal.valueOf(100), List.of(registroInexistente));

        assertThatThrownBy(() -> service.registrarYConciliar(dto))
                .isInstanceOf(RecursoNoEncontradoException.class);

        verify(repository, never()).save(any());
        verify(inventarioLecheService, never()).registrarEntradaRecepcion(any());
    }

    // Fix trazabilidad (confirmado): mismo patron que
    // LoteProduccionServiceTest.rechazaVincularUnRegistroQueYaPerteneceAOtroLote.
    @Test
    void rechazaVincularUnRegistroAcopioQueYaPerteneceAOtraRecepcion() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("recepcion@planta.com", null));

        UUID unidadId = UUID.randomUUID();
        UUID registroYaVinculado = UUID.randomUUID();

        when(repository.findByFechaAndUnidadIdAndTurno(any(), eq(unidadId), any())).thenReturn(Optional.empty());

        Unidad unidad = new Unidad();
        unidad.setId(unidadId);
        when(unidadRepository.findById(unidadId)).thenReturn(Optional.of(unidad));

        Usuario responsable = new Usuario();
        responsable.setId(UUID.randomUUID());
        when(usuarioRepository.findByEmail("recepcion@planta.com")).thenReturn(Optional.of(responsable));

        when(repository.existeRegistroYaVinculado(registroYaVinculado)).thenReturn(true);

        var dto = new RecepcionPlantaRequest(LocalDate.now(), null, unidadId,
                BigDecimal.valueOf(100), BigDecimal.valueOf(100), List.of(registroYaVinculado));

        assertThatThrownBy(() -> service.registrarYConciliar(dto))
                .isInstanceOf(ValidacionNegocioException.class);

        verify(repository, never()).save(any());
        verify(inventarioLecheService, never()).registrarEntradaRecepcion(any());
    }

    // Bloque 5 (confirmado): filtro opcional por fecha, combinable con el de
    // unidad ya existente -- sin parametros, mismo resultado que antes.
    @Test
    void listarSinFiltrosPropagaAmbosParametrosComoNull() {
        Pageable pageable = PageRequest.of(0, 20);
        when(repository.buscarConFiltros(isNull(), isNull(), isNull(), eq(pageable))).thenReturn(new PageImpl<>(List.of()));

        service.listar(null, null, null, pageable);

        verify(repository).buscarConFiltros(null, null, null, pageable);
    }

    // ---------------------------------------------------------------------------------------------
    // Fase 2 (docs/ESPEC_JORNADA_TRAZABILIDAD.md, Ecolacteos-Frontend): registrarRecepcionDeJornada.
    // ---------------------------------------------------------------------------------------------

    private Jornada jornadaRegresandoAPlanta(UUID id, BigDecimal capacidadTon) {
        Jornada jornada = new Jornada();
        jornada.setId(id);
        jornada.setUuidCliente(UUID.randomUUID().toString());
        jornada.setEstado(EstadoJornada.REGRESANDO_A_PLANTA);
        jornada.setHoraInicio(LocalDateTime.now().minusHours(3));
        jornada.setHoraFin(LocalDateTime.now().minusMinutes(30));
        Unidad unidad = new Unidad();
        unidad.setId(UUID.randomUUID());
        unidad.setPlaca("ABC-123");
        unidad.setCapacidadTon(capacidadTon);
        jornada.setUnidad(unidad);
        Usuario acopiador = new Usuario();
        acopiador.setId(UUID.randomUUID());
        acopiador.setNombre("Carlos Acopiador");
        jornada.setAcopiador(acopiador);
        return jornada;
    }

    private RegistroAcopio acopioDe(BigDecimal litros) {
        RegistroAcopio r = new RegistroAcopio();
        r.setId(UUID.randomUUID());
        r.setLitros(litros);
        return r;
    }

    private void stubUsuarioAutenticado() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("recepcion@planta.com", null));
        Usuario responsable = new Usuario();
        responsable.setId(UUID.randomUUID());
        when(usuarioRepository.findByEmail("recepcion@planta.com")).thenReturn(Optional.of(responsable));
    }

    @Test
    void siUuidClienteYaExisteDevuelveLaRecepcionExistenteSinCrearDuplicado() {
        String uuidCliente = UUID.randomUUID().toString();
        RecepcionPlanta existente = new RecepcionPlanta();
        existente.setId(UUID.randomUUID());
        existente.setFecha(LocalDate.now());
        existente.setTurno("UNICO");
        Unidad unidad = new Unidad();
        unidad.setId(UUID.randomUUID());
        existente.setUnidad(unidad);
        existente.setLitrosCampo(BigDecimal.valueOf(80));
        existente.setLitrosPlanta(BigDecimal.valueOf(80));
        existente.setEstado(EstadoConciliacion.OK);
        when(repository.findByUuidCliente(uuidCliente)).thenReturn(Optional.of(existente));
        when(registroAcopioRepository.sumLitrosPorUnidadYFecha(any(), any(), any())).thenReturn(BigDecimal.ZERO);

        var dto = new RecepcionJornadaRequest(uuidCliente, UUID.randomUUID(), BigDecimal.valueOf(80),
                null, LocalDateTime.now(), null);

        var resultado = service.registrarRecepcionDeJornada(dto);

        assertThat(resultado.id()).isEqualTo(existente.getId());
        verify(repository, never()).saveAndFlush(any());
        verify(jornadaRepository, never()).findById(any());
        // Requisito explicito: un reintento de red del movil (mismo uuidCliente) NUNCA debe duplicar la
        // entrada de leche -- la idempotencia por uuidCliente corta el flujo antes de tocar el libro mayor.
        verify(inventarioLecheService, never()).registrarEntradaRecepcion(any());
    }

    @Test
    void rechazaJornadaInexistente() {
        String uuidCliente = UUID.randomUUID().toString();
        UUID jornadaId = UUID.randomUUID();
        when(repository.findByUuidCliente(uuidCliente)).thenReturn(Optional.empty());
        when(jornadaRepository.findById(jornadaId)).thenReturn(Optional.empty());

        var dto = new RecepcionJornadaRequest(uuidCliente, jornadaId, BigDecimal.valueOf(80),
                null, LocalDateTime.now(), null);

        assertThatThrownBy(() -> service.registrarRecepcionDeJornada(dto))
                .isInstanceOf(RecursoNoEncontradoException.class);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void rechazaJornadaEnRecoleccion() {
        String uuidCliente = UUID.randomUUID().toString();
        UUID jornadaId = UUID.randomUUID();
        Jornada jornada = jornadaRegresandoAPlanta(jornadaId, BigDecimal.TEN);
        jornada.setEstado(EstadoJornada.EN_RECOLECCION);
        when(repository.findByUuidCliente(uuidCliente)).thenReturn(Optional.empty());
        when(jornadaRepository.findById(jornadaId)).thenReturn(Optional.of(jornada));

        var dto = new RecepcionJornadaRequest(uuidCliente, jornadaId, BigDecimal.valueOf(80),
                null, LocalDateTime.now(), null);

        assertThatThrownBy(() -> service.registrarRecepcionDeJornada(dto))
                .isInstanceOf(ValidacionNegocioException.class);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void rechazaJornadaCancelada() {
        String uuidCliente = UUID.randomUUID().toString();
        UUID jornadaId = UUID.randomUUID();
        Jornada jornada = jornadaRegresandoAPlanta(jornadaId, BigDecimal.TEN);
        jornada.setEstado(EstadoJornada.CANCELADA);
        when(repository.findByUuidCliente(uuidCliente)).thenReturn(Optional.empty());
        when(jornadaRepository.findById(jornadaId)).thenReturn(Optional.of(jornada));

        var dto = new RecepcionJornadaRequest(uuidCliente, jornadaId, BigDecimal.valueOf(80),
                null, LocalDateTime.now(), null);

        assertThatThrownBy(() -> service.registrarRecepcionDeJornada(dto))
                .isInstanceOf(ValidacionNegocioException.class);
    }

    @Test
    void rechazaJornadaYaRecibida() {
        String uuidCliente = UUID.randomUUID().toString();
        UUID jornadaId = UUID.randomUUID();
        Jornada jornada = jornadaRegresandoAPlanta(jornadaId, BigDecimal.TEN);
        jornada.setEstado(EstadoJornada.RECIBIDA);
        when(repository.findByUuidCliente(uuidCliente)).thenReturn(Optional.empty());
        when(jornadaRepository.findById(jornadaId)).thenReturn(Optional.of(jornada));

        var dto = new RecepcionJornadaRequest(uuidCliente, jornadaId, BigDecimal.valueOf(80),
                null, LocalDateTime.now(), null);

        assertThatThrownBy(() -> service.registrarRecepcionDeJornada(dto))
                .isInstanceOf(ValidacionNegocioException.class);
    }

    @Test
    void rechazaSegundaRecepcionDeLaMismaJornada() {
        String uuidCliente = UUID.randomUUID().toString();
        UUID jornadaId = UUID.randomUUID();
        Jornada jornada = jornadaRegresandoAPlanta(jornadaId, BigDecimal.TEN);
        when(repository.findByUuidCliente(uuidCliente)).thenReturn(Optional.empty());
        when(jornadaRepository.findById(jornadaId)).thenReturn(Optional.of(jornada));
        when(repository.existsByJornadaId(jornadaId)).thenReturn(true);

        var dto = new RecepcionJornadaRequest(uuidCliente, jornadaId, BigDecimal.valueOf(80),
                null, LocalDateTime.now(), null);

        assertThatThrownBy(() -> service.registrarRecepcionDeJornada(dto))
                .isInstanceOf(ConflictoNegocioException.class);
        verify(repository, never()).saveAndFlush(any());
        verify(jornadaRepository, never()).save(any());
    }

    @Test
    void unaViolacionDeIndiceUnicoEnLaCarreraSeTraduceAConflicto() {
        stubUsuarioAutenticado();
        String uuidCliente = UUID.randomUUID().toString();
        UUID jornadaId = UUID.randomUUID();
        Jornada jornada = jornadaRegresandoAPlanta(jornadaId, BigDecimal.TEN);
        when(repository.findByUuidCliente(uuidCliente)).thenReturn(Optional.empty());
        when(jornadaRepository.findById(jornadaId)).thenReturn(Optional.of(jornada));
        when(repository.existsByJornadaId(jornadaId)).thenReturn(false);
        when(registroAcopioRepository.findByJornadaIdOrderByFechaHoraAsc(jornadaId)).thenReturn(List.of());
        when(repository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("indice unico violado"));

        var dto = new RecepcionJornadaRequest(uuidCliente, jornadaId, BigDecimal.valueOf(80),
                null, LocalDateTime.now(), null);

        assertThatThrownBy(() -> service.registrarRecepcionDeJornada(dto))
                .isInstanceOf(ConflictoNegocioException.class);
        verify(jornadaRepository, never()).save(any());
        // La carrera revierte toda la transaccion -- ninguna entrada de leche a medio registrar.
        verify(inventarioLecheService, never()).registrarEntradaRecepcion(any());
    }

    @Test
    void calculaLitrosEsperadosComoSumaDeLosAcopiosDeLaJornadaYMarcaLaJornadaRecibida() {
        stubUsuarioAutenticado();
        String uuidCliente = UUID.randomUUID().toString();
        UUID jornadaId = UUID.randomUUID();
        Jornada jornada = jornadaRegresandoAPlanta(jornadaId, BigDecimal.TEN);
        List<RegistroAcopio> acopios = List.of(acopioDe(BigDecimal.valueOf(50)), acopioDe(BigDecimal.valueOf(30)));
        when(repository.findByUuidCliente(uuidCliente)).thenReturn(Optional.empty());
        when(jornadaRepository.findById(jornadaId)).thenReturn(Optional.of(jornada));
        when(repository.existsByJornadaId(jornadaId)).thenReturn(false);
        when(registroAcopioRepository.findByJornadaIdOrderByFechaHoraAsc(jornadaId)).thenReturn(acopios);
        when(repository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        when(registroAcopioRepository.sumLitrosPorUnidadYFecha(any(), any(), any())).thenReturn(BigDecimal.ZERO);
        when(configuracionSistemaService.valorDe("UMBRAL_CONCILIACION_PCT")).thenReturn("2.5");

        var horaLlegada = LocalDateTime.now();
        var dto = new RecepcionJornadaRequest(uuidCliente, jornadaId, BigDecimal.valueOf(82),
                "TARDE", horaLlegada, "2 L de diferencia por derrame menor");

        var resultado = service.registrarRecepcionDeJornada(dto);

        assertThat(resultado.litrosCampo()).isEqualByComparingTo(BigDecimal.valueOf(80));
        assertThat(resultado.litrosPlanta()).isEqualByComparingTo(BigDecimal.valueOf(82));
        assertThat(resultado.diferenciaLitros()).isEqualByComparingTo(BigDecimal.valueOf(2));
        assertThat(resultado.jornadaId()).isEqualTo(jornadaId);
        assertThat(resultado.horaLlegada()).isEqualTo(horaLlegada);
        assertThat(resultado.observacion()).isEqualTo("2 L de diferencia por derrame menor");
        assertThat(resultado.registroAcopioIds()).hasSize(2);
        assertThat(jornada.getEstado()).isEqualTo(EstadoJornada.RECIBIDA);
        verify(jornadaRepository).save(jornada);

        // Fase 1 del refactor de Produccion: la recepcion de jornada alimenta la existencia de leche con
        // litrosPlanta (82, lo medido), NUNCA litrosCampo (80, el esperado por SUM de acopios).
        ArgumentCaptor<RecepcionPlanta> captor = ArgumentCaptor.forClass(RecepcionPlanta.class);
        verify(inventarioLecheService).registrarEntradaRecepcion(captor.capture());
        assertThat(captor.getValue().getLitrosPlanta()).isEqualByComparingTo(BigDecimal.valueOf(82));
    }

    @Test
    void litrosEsperadosCeroSinAcopiosNoRompeElCalculo() {
        stubUsuarioAutenticado();
        String uuidCliente = UUID.randomUUID().toString();
        UUID jornadaId = UUID.randomUUID();
        Jornada jornada = jornadaRegresandoAPlanta(jornadaId, BigDecimal.TEN);
        when(repository.findByUuidCliente(uuidCliente)).thenReturn(Optional.empty());
        when(jornadaRepository.findById(jornadaId)).thenReturn(Optional.of(jornada));
        when(repository.existsByJornadaId(jornadaId)).thenReturn(false);
        when(registroAcopioRepository.findByJornadaIdOrderByFechaHoraAsc(jornadaId)).thenReturn(List.of());
        when(repository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        when(registroAcopioRepository.sumLitrosPorUnidadYFecha(any(), any(), any())).thenReturn(BigDecimal.ZERO);
        when(configuracionSistemaService.valorDe("UMBRAL_CONCILIACION_PCT")).thenReturn("2.5");

        var dto = new RecepcionJornadaRequest(uuidCliente, jornadaId, BigDecimal.ZERO,
                null, LocalDateTime.now(), null);

        var resultado = service.registrarRecepcionDeJornada(dto);

        assertThat(resultado.litrosCampo()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(resultado.registroAcopioIds()).isEmpty();
        assertThat(jornada.getEstado()).isEqualTo(EstadoJornada.RECIBIDA);
    }

    @Test
    void listarPliegaElTextoSinAcentosNiMayusculas() {
        Pageable pageable = PageRequest.of(0, 20);
        when(repository.buscarConFiltros(isNull(), isNull(), eq("%nina%"), eq(pageable))).thenReturn(new PageImpl<>(List.of()));

        service.listar(null, null, "NIÑA", pageable);

        verify(repository).buscarConFiltros(null, null, "%nina%", pageable);
    }
}
