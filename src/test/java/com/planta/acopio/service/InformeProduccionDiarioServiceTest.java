package com.planta.acopio.service;

import com.planta.acopio.dto.CrearInformeProduccionRequest;
import com.planta.acopio.dto.EditarInformeProduccionRequest;
import com.planta.acopio.dto.InsumoUsadoRequest;
import com.planta.acopio.entity.InformeProduccionDiario;
import com.planta.acopio.entity.InformeProduccionInsumo;
import com.planta.acopio.entity.Insumo;
import com.planta.acopio.entity.Usuario;
import com.planta.acopio.exception.ConflictoNegocioException;
import com.planta.acopio.exception.RecursoNoEncontradoException;
import com.planta.acopio.exception.ValidacionNegocioException;
import com.planta.acopio.repository.InformeProduccionDiarioRepository;
import com.planta.acopio.repository.InsumoRepository;
import com.planta.acopio.repository.UsuarioRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InformeProduccionDiarioServiceTest {

    @Mock private InformeProduccionDiarioRepository repository;
    @Mock private InsumoRepository insumoRepository;
    @Mock private UsuarioRepository usuarioRepository;

    private InformeProduccionDiarioService service;
    private final UUID insumoId = UUID.randomUUID();
    private final Pageable pageable = PageRequest.of(0, 20);

    @BeforeEach
    void setUp() {
        service = new InformeProduccionDiarioService(repository, insumoRepository, usuarioRepository);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("produccion@planta.com", null));
    }

    private Usuario responsable() {
        Usuario u = new Usuario();
        u.setId(UUID.randomUUID());
        u.setEmail("produccion@planta.com");
        u.setNombre("Responsable Produccion");
        return u;
    }

    private CrearInformeProduccionRequest dtoValido(String uuidCliente, LocalDate fecha) {
        return new CrearInformeProduccionRequest(uuidCliente, fecha, 1195, 5, 4,
                BigDecimal.valueOf(9), List.of(new InsumoUsadoRequest(insumoId, BigDecimal.valueOf(108))));
    }

    @Test
    void siUuidClienteYaExisteDevuelveElExistenteSinCrearDuplicado() {
        String uuidCliente = UUID.randomUUID().toString();
        InformeProduccionDiario existente = new InformeProduccionDiario();
        existente.setId(UUID.randomUUID());
        existente.setUuidCliente(uuidCliente);
        existente.setFecha(LocalDate.of(2026, 9, 1));
        existente.setQuesosDiarios(1195);
        existente.setQuesosReparados(5);
        existente.setQuesosDeRecorte(4);
        existente.setResponsable(responsable());
        when(repository.findByUuidCliente(uuidCliente)).thenReturn(Optional.of(existente));

        var respuesta = service.crear(dtoValido(uuidCliente, LocalDate.of(2026, 9, 1)));

        assertThat(respuesta.uuidCliente()).isEqualTo(uuidCliente);
        verify(repository, never()).save(any());
        verifyNoInteractions(insumoRepository);
    }

    @Test
    void rechazaSegundoInformeParaLaMismaFecha() {
        String uuidCliente = UUID.randomUUID().toString();
        LocalDate fecha = LocalDate.now();
        when(repository.findByUuidCliente(uuidCliente)).thenReturn(Optional.empty());
        InformeProduccionDiario yaExiste = new InformeProduccionDiario();
        yaExiste.setFecha(fecha);
        when(repository.findByFecha(fecha)).thenReturn(Optional.of(yaExiste));

        assertThatThrownBy(() -> service.crear(dtoValido(uuidCliente, fecha)))
                .isInstanceOf(ConflictoNegocioException.class)
                .hasMessageContaining(fecha.toString());
        verify(repository, never()).save(any());
    }

    @Test
    void crearFechaPasadaEsRechazada() {
        String uuidCliente = UUID.randomUUID().toString();
        LocalDate ayer = LocalDate.now().minusDays(1);
        when(repository.findByUuidCliente(uuidCliente)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.crear(dtoValido(uuidCliente, ayer)))
                .isInstanceOf(ValidacionNegocioException.class)
                .hasMessageContaining("fecha de hoy");
        verify(repository, never()).findByFecha(any());
        verify(repository, never()).save(any());
    }

    @Test
    void crearFechaFuturaEsRechazada() {
        String uuidCliente = UUID.randomUUID().toString();
        LocalDate manana = LocalDate.now().plusDays(1);
        when(repository.findByUuidCliente(uuidCliente)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.crear(dtoValido(uuidCliente, manana)))
                .isInstanceOf(ValidacionNegocioException.class)
                .hasMessageContaining("fecha de hoy");
        verify(repository, never()).findByFecha(any());
        verify(repository, never()).save(any());
    }

    // Carrera real (dos requests concurrentes con distinto uuidCliente, misma fecha): ambos pasan
    // findByFecha() antes de que el primero confirme su INSERT -- el UNIQUE(fecha) de la BD frena al
    // segundo save(). No se simula con threads reales (fragil); se fuerza el mismo resultado observable
    // estubeando save() para que lance la excepcion que Postgres lanzaria en ese escenario.
    @Test
    void carreraConcurrenteEnSaveSeTraduceAConflictoNoA500() {
        String uuidCliente = UUID.randomUUID().toString();
        LocalDate hoy = LocalDate.now();
        when(repository.findByUuidCliente(uuidCliente)).thenReturn(Optional.empty());
        when(repository.findByFecha(hoy)).thenReturn(Optional.empty());
        when(usuarioRepository.findByEmail("produccion@planta.com")).thenReturn(Optional.of(responsable()));
        when(repository.save(any())).thenThrow(new DataIntegrityViolationException("unique constraint informe_produccion_diario_fecha_key"));

        var dto = new CrearInformeProduccionRequest(uuidCliente, hoy, 100, 0, 0, null, List.of());

        assertThatThrownBy(() -> service.crear(dto))
                .isInstanceOf(ConflictoNegocioException.class)
                .hasMessageContaining(hoy.toString());
    }

    @Test
    void crearRechazaInsumoDuplicadoEnElMismoInforme() {
        String uuidCliente = UUID.randomUUID().toString();
        LocalDate hoy = LocalDate.now();
        when(repository.findByUuidCliente(uuidCliente)).thenReturn(Optional.empty());
        when(repository.findByFecha(hoy)).thenReturn(Optional.empty());
        when(usuarioRepository.findByEmail("produccion@planta.com")).thenReturn(Optional.of(responsable()));
        // La deteccion de duplicados es una pasada previa (validarSinInsumosDuplicados), antes de resolver
        // ningun insumo -- insumoRepository.findById nunca llega a llamarse.

        var dto = new CrearInformeProduccionRequest(uuidCliente, hoy, 100, 0, 0, null, List.of(
                new InsumoUsadoRequest(insumoId, BigDecimal.valueOf(10)),
                new InsumoUsadoRequest(insumoId, BigDecimal.valueOf(20))
        ));

        assertThatThrownBy(() -> service.crear(dto))
                .isInstanceOf(ValidacionNegocioException.class)
                .hasMessageContaining("repetido");
        verify(repository, never()).save(any());
        verifyNoInteractions(insumoRepository);
    }

    @Test
    void crearConDosInsumosDiferentesFunciona() {
        String uuidCliente = UUID.randomUUID().toString();
        LocalDate hoy = LocalDate.now();
        UUID otroInsumoId = UUID.randomUUID();
        when(repository.findByUuidCliente(uuidCliente)).thenReturn(Optional.empty());
        when(repository.findByFecha(hoy)).thenReturn(Optional.empty());
        when(usuarioRepository.findByEmail("produccion@planta.com")).thenReturn(Optional.of(responsable()));
        Insumo cuajo = new Insumo();
        cuajo.setId(insumoId);
        cuajo.setNombre("Cuajo");
        cuajo.setUnidadMedida("unidades");
        Insumo sal = new Insumo();
        sal.setId(otroInsumoId);
        sal.setNombre("Sal");
        sal.setUnidadMedida("sacos");
        when(insumoRepository.findById(insumoId)).thenReturn(Optional.of(cuajo));
        when(insumoRepository.findById(otroInsumoId)).thenReturn(Optional.of(sal));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var dto = new CrearInformeProduccionRequest(uuidCliente, hoy, 100, 0, 0, null, List.of(
                new InsumoUsadoRequest(insumoId, BigDecimal.valueOf(10)),
                new InsumoUsadoRequest(otroInsumoId, BigDecimal.valueOf(2))
        ));

        var respuesta = service.crear(dto);

        assertThat(respuesta.insumos()).hasSize(2);
        assertThat(respuesta.insumos()).extracting("insumoId").containsExactlyInAnyOrder(insumoId, otroInsumoId);
    }

    @Test
    void crearSinInsumosFuncionaConListaVacia() {
        String uuidCliente = UUID.randomUUID().toString();
        LocalDate hoy = LocalDate.now();
        when(repository.findByUuidCliente(uuidCliente)).thenReturn(Optional.empty());
        when(repository.findByFecha(hoy)).thenReturn(Optional.empty());
        when(usuarioRepository.findByEmail("produccion@planta.com")).thenReturn(Optional.of(responsable()));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var dto = new CrearInformeProduccionRequest(uuidCliente, hoy, 100, 0, 0, null, List.of());

        var respuesta = service.crear(dto);

        assertThat(respuesta.insumos()).isEmpty();
    }

    @Test
    void creaInformeConInsumosYCalculaTotalQuesos() {
        String uuidCliente = UUID.randomUUID().toString();
        LocalDate fecha = LocalDate.now();
        when(repository.findByUuidCliente(uuidCliente)).thenReturn(Optional.empty());
        when(repository.findByFecha(fecha)).thenReturn(Optional.empty());
        Insumo cuajo = new Insumo();
        cuajo.setId(insumoId);
        cuajo.setNombre("Cuajo");
        cuajo.setUnidadMedida("unidades");
        when(insumoRepository.findById(insumoId)).thenReturn(Optional.of(cuajo));
        when(usuarioRepository.findByEmail("produccion@planta.com")).thenReturn(Optional.of(responsable()));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var respuesta = service.crear(dtoValido(uuidCliente, fecha));

        assertThat(respuesta.totalQuesos()).isEqualTo(1195 + 5 + 4);
        assertThat(respuesta.insumos()).hasSize(1);
        assertThat(respuesta.insumos().get(0).cantidad()).isEqualByComparingTo(BigDecimal.valueOf(108));
    }

    @Test
    void crearRechazaInsumoInexistente() {
        String uuidCliente = UUID.randomUUID().toString();
        LocalDate fecha = LocalDate.now();
        when(repository.findByUuidCliente(uuidCliente)).thenReturn(Optional.empty());
        when(repository.findByFecha(fecha)).thenReturn(Optional.empty());
        when(usuarioRepository.findByEmail("produccion@planta.com")).thenReturn(Optional.of(responsable()));
        when(insumoRepository.findById(insumoId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.crear(dtoValido(uuidCliente, fecha)))
                .isInstanceOf(RecursoNoEncontradoException.class)
                .hasMessageContaining("Insumo no encontrado");
        verify(repository, never()).save(any());
    }

    @Test
    void editarReemplazaValoresEInsumosYRegistraQuienEdito() {
        UUID id = UUID.randomUUID();
        InformeProduccionDiario informe = new InformeProduccionDiario();
        informe.setId(id);
        informe.setFecha(LocalDate.now());
        informe.setQuesosDiarios(1185);
        informe.setQuesosReparados(12);
        informe.setQuesosDeRecorte(2);
        informe.setResponsable(responsable());
        when(repository.findById(id)).thenReturn(Optional.of(informe));

        Insumo sal = new Insumo();
        sal.setId(insumoId);
        sal.setNombre("Sal");
        sal.setUnidadMedida("sacos");
        when(insumoRepository.findById(insumoId)).thenReturn(Optional.of(sal));

        Usuario editor = responsable();
        editor.setNombre("Otro Usuario");
        when(usuarioRepository.findByEmail("produccion@planta.com")).thenReturn(Optional.of(editor));

        var dto = new EditarInformeProduccionRequest(1185, 12, 4,
                BigDecimal.valueOf(6), List.of(new InsumoUsadoRequest(insumoId, BigDecimal.valueOf(2))));

        var respuesta = service.editar(id, dto);

        assertThat(respuesta.quesosDeRecorte()).isEqualTo(4);
        assertThat(respuesta.recorteEnKilos()).isEqualByComparingTo(BigDecimal.valueOf(6));
        assertThat(respuesta.insumos()).hasSize(1);
        assertThat(respuesta.actualizadoPorNombre()).isEqualTo("Otro Usuario");
        assertThat(respuesta.actualizadoEn()).isNotNull();
    }

    // Bug real reportado en vivo: editar un informe que YA tenia insumos cargados devolvia 500 ("Error
    // interno inesperado"). Causa: la version anterior hacia informe.getInsumos().clear() + addAll(nuevos),
    // que con una coleccion @OneToMany(orphanRemoval=true) hace que Hibernate agende el INSERT del
    // reemplazo ANTES que el DELETE del original en el mismo flush -- viola UNIQUE(informe_id, insumo_id)
    // de forma transitoria. La correccion reconcilia en el lugar. Este test no usa una BD real (no hay
    // Docker en este entorno), pero prueba la garantia a nivel de objeto: si el codigo volviera a
    // clear()+addAll(), este test fallaria (ver comentario en cada aserto de por que).
    @Test
    void editarConElMismoInsumoActualizaLaCantidadEnElMismoObjetoSinBorrarloYRecrearlo() {
        UUID id = UUID.randomUUID();
        InformeProduccionDiario informe = new InformeProduccionDiario();
        informe.setId(id);
        informe.setFecha(LocalDate.now());
        informe.setQuesosDiarios(20);
        informe.setQuesosReparados(3);
        informe.setQuesosDeRecorte(0);
        informe.setResponsable(responsable());

        Insumo cuajo = new Insumo();
        cuajo.setId(insumoId);
        cuajo.setNombre("Cuajo");
        cuajo.setUnidadMedida("unidades");
        InformeProduccionInsumo detalleExistente = new InformeProduccionInsumo();
        detalleExistente.setId(UUID.randomUUID());
        detalleExistente.setInforme(informe);
        detalleExistente.setInsumo(cuajo);
        detalleExistente.setCantidad(BigDecimal.ONE);
        informe.setInsumos(new HashSet<>(Set.of(detalleExistente)));

        when(repository.findById(id)).thenReturn(Optional.of(informe));
        when(usuarioRepository.findByEmail("produccion@planta.com")).thenReturn(Optional.of(responsable()));
        // A proposito NO se estubea insumoRepository.findById(insumoId): la reconciliacion correcta nunca
        // deberia volver a buscarlo (el insumo ya usado sigue en la coleccion, solo cambia su cantidad). Si
        // el codigo regresara a clear()+addAll() (que reconstruye TODAS las filas desde cero), esta llamada
        // no estubeada devolvería Optional.empty() y el test fallaría con RecursoNoEncontradoException.
        var dto = new EditarInformeProduccionRequest(25, 3, 0, null, List.of(new InsumoUsadoRequest(insumoId, BigDecimal.TEN)));

        var respuesta = service.editar(id, dto);

        assertThat(informe.getInsumos()).hasSize(1);
        assertThat(informe.getInsumos()).containsExactly(detalleExistente); // misma instancia: no se borró/recreó
        assertThat(detalleExistente.getCantidad()).isEqualByComparingTo(BigDecimal.TEN); // actualizada in-place
        assertThat(respuesta.insumos()).hasSize(1);
        assertThat(respuesta.insumos().get(0).cantidad()).isEqualByComparingTo(BigDecimal.TEN);
    }

    @Test
    void editarQuitaUnInsumoQueYaNoVieneEnLaListaNueva() {
        UUID id = UUID.randomUUID();
        InformeProduccionDiario informe = new InformeProduccionDiario();
        informe.setId(id);
        informe.setFecha(LocalDate.now());
        informe.setQuesosDiarios(20);
        informe.setResponsable(responsable());

        Insumo cuajo = new Insumo();
        cuajo.setId(insumoId);
        InformeProduccionInsumo detalleExistente = new InformeProduccionInsumo();
        detalleExistente.setId(UUID.randomUUID());
        detalleExistente.setInforme(informe);
        detalleExistente.setInsumo(cuajo);
        detalleExistente.setCantidad(BigDecimal.ONE);
        informe.setInsumos(new HashSet<>(Set.of(detalleExistente)));

        when(repository.findById(id)).thenReturn(Optional.of(informe));
        when(usuarioRepository.findByEmail("produccion@planta.com")).thenReturn(Optional.of(responsable()));

        var dto = new EditarInformeProduccionRequest(20, 0, 0, null, List.of()); // sin insumos

        service.editar(id, dto);

        assertThat(informe.getInsumos()).isEmpty();
    }

    @Test
    void editarAgregaUnInsumoNuevoQueNoExistiaAntesSinTocarElExistente() {
        UUID id = UUID.randomUUID();
        UUID insumoNuevoId = UUID.randomUUID();
        InformeProduccionDiario informe = new InformeProduccionDiario();
        informe.setId(id);
        informe.setFecha(LocalDate.now());
        informe.setQuesosDiarios(20);
        informe.setResponsable(responsable());

        Insumo cuajo = new Insumo();
        cuajo.setId(insumoId);
        InformeProduccionInsumo detalleExistente = new InformeProduccionInsumo();
        detalleExistente.setId(UUID.randomUUID());
        detalleExistente.setInforme(informe);
        detalleExistente.setInsumo(cuajo);
        detalleExistente.setCantidad(BigDecimal.ONE);
        informe.setInsumos(new HashSet<>(Set.of(detalleExistente)));

        Insumo sal = new Insumo();
        sal.setId(insumoNuevoId);
        sal.setNombre("Sal");
        sal.setUnidadMedida("sacos");

        when(repository.findById(id)).thenReturn(Optional.of(informe));
        when(usuarioRepository.findByEmail("produccion@planta.com")).thenReturn(Optional.of(responsable()));
        when(insumoRepository.findById(insumoNuevoId)).thenReturn(Optional.of(sal));

        var dto = new EditarInformeProduccionRequest(20, 0, 0, null, List.of(
                new InsumoUsadoRequest(insumoId, BigDecimal.ONE), // el mismo de antes, sin cambios
                new InsumoUsadoRequest(insumoNuevoId, BigDecimal.valueOf(2)) // nuevo
        ));

        service.editar(id, dto);

        assertThat(informe.getInsumos()).hasSize(2);
        assertThat(informe.getInsumos()).contains(detalleExistente); // el que ya estaba sigue siendo la misma instancia
        verify(insumoRepository, never()).findById(insumoId); // el existente NUNCA se vuelve a buscar
    }

    @Test
    void editarInformePasadoEsRechazado() {
        UUID id = UUID.randomUUID();
        InformeProduccionDiario informe = new InformeProduccionDiario();
        informe.setId(id);
        informe.setFecha(LocalDate.now().minusDays(1));
        when(repository.findById(id)).thenReturn(Optional.of(informe));

        var dto = new EditarInformeProduccionRequest(100, 0, 0, null, List.of());

        assertThatThrownBy(() -> service.editar(id, dto))
                .isInstanceOf(ValidacionNegocioException.class)
                .hasMessageContaining("dia de hoy");
        verifyNoInteractions(usuarioRepository);
    }

    // "Si existiera por datos antiguos" (pedido explicito): el chequeo es simetrico (!isEqual(hoy)), asi
    // que tambien cubre una fecha futura, aunque en el flujo normal nunca deberia poder crearse una.
    @Test
    void editarInformeFuturoEsRechazado() {
        UUID id = UUID.randomUUID();
        InformeProduccionDiario informe = new InformeProduccionDiario();
        informe.setId(id);
        informe.setFecha(LocalDate.now().plusDays(1));
        when(repository.findById(id)).thenReturn(Optional.of(informe));

        var dto = new EditarInformeProduccionRequest(100, 0, 0, null, List.of());

        assertThatThrownBy(() -> service.editar(id, dto))
                .isInstanceOf(ValidacionNegocioException.class)
                .hasMessageContaining("dia de hoy");
    }

    @Test
    void editarLanzaSiNoExiste() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());

        var dto = new EditarInformeProduccionRequest(100, 0, 0, null, List.of());

        assertThatThrownBy(() -> service.editar(id, dto))
                .isInstanceOf(RecursoNoEncontradoException.class);
    }

    @Test
    void obtenerPorFechaLanzaSiNoExisteInformeEseDia() {
        LocalDate fecha = LocalDate.of(2026, 9, 30);
        when(repository.findByFecha(fecha)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.obtenerPorFecha(fecha))
                .isInstanceOf(RecursoNoEncontradoException.class);
    }

    @Test
    void buscarSinFiltrosPropagaAmbosParametrosComoNull() {
        when(repository.buscarConFiltros(isNull(), isNull(), isNull(), eq(pageable))).thenReturn(new PageImpl<>(List.of()));

        service.buscar(null, null, null, pageable);

        verify(repository).buscarConFiltros(null, null, null, pageable);
    }

    @Test
    void buscarPliegaElTextoSinAcentosNiMayusculas() {
        when(repository.buscarConFiltros(isNull(), isNull(), eq("%jose%"), eq(pageable))).thenReturn(new PageImpl<>(List.of()));

        service.buscar(null, null, "JOSÉ", pageable);

        verify(repository).buscarConFiltros(null, null, "%jose%", pageable);
    }
}
