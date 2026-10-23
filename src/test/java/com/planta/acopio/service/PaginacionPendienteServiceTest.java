package com.planta.acopio.service;

import com.planta.acopio.entity.PrecioLitro;
import com.planta.acopio.entity.Rol;
import com.planta.acopio.entity.Usuario;
import com.planta.acopio.repository.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Paginacion pendiente: los services delegan en las consultas paginadas de la BD (con orden estable) y
 * conservan el total real que devuelve el count, no el tamaño de la pagina.
 */
@ExtendWith(MockitoExtension.class)
class PaginacionPendienteServiceTest {

    @Mock private PagoRepository pagoRepository;
    @Mock private ProveedorRepository proveedorRepository;
    @Mock private RegistroAcopioRepository registroAcopioRepository;
    @Mock private PrecioLitroRepository precioLitroRepository;
    @Mock private AjustePrecioPagoRepository ajustePrecioRepository;
    @Mock private EntityManager entityManager;
    @Mock private ComunicadoRepository comunicadoRepository;
    @Mock private ZonaRepository zonaRepository;
    @Mock private UsuarioRepository usuarioRepository;
    @Mock private ComunicadoConfirmacionRepository confirmacionRepository;
    @Mock private RolRepository rolRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private RefreshTokenService refreshTokenService;

    private final PageRequest pageable = PageRequest.of(3, 10);

    @Test
    void pagosListarUsaLaConsultaPaginadaYConservaElTotal() {
        PagoService service = new PagoService(pagoRepository, proveedorRepository, registroAcopioRepository,
                precioLitroRepository, ajustePrecioRepository, entityManager, null, null);
        when(pagoRepository.buscar(null, pageable)).thenReturn(new PageImpl<>(List.of(), pageable, 95));

        var pagina = service.listar(null, pageable);

        assertThat(pagina.getTotalElements()).isEqualTo(95);
        assertThat(pagina.getTotalPages()).isEqualTo(10);
        assertThat(pagina.getNumber()).isEqualTo(3);
    }

    @Test
    void pagosPorProveedorPaginadoFiltraPorProveedor() {
        PagoService service = new PagoService(pagoRepository, proveedorRepository, registroAcopioRepository,
                precioLitroRepository, ajustePrecioRepository, entityManager, null, null);
        UUID proveedorId = UUID.randomUUID();
        when(pagoRepository.findByProveedorIdOrderBySemanaInicioDescIdDesc(proveedorId, pageable))
                .thenReturn(new PageImpl<>(List.of(), pageable, 31));

        var pagina = service.historialPorProveedor(proveedorId, pageable);

        assertThat(pagina.getTotalElements()).isEqualTo(31);
        verify(pagoRepository).findByProveedorIdOrderBySemanaInicioDescIdDesc(proveedorId, pageable);
    }

    @Test
    void comunicadosListarUsaLaConsultaPaginada() {
        ComunicadoService service = new ComunicadoService(comunicadoRepository, zonaRepository, proveedorRepository,
                usuarioRepository, confirmacionRepository);
        when(comunicadoRepository.buscar(null, pageable)).thenReturn(new PageImpl<>(List.of(), pageable, 12));

        assertThat(service.listar(null, pageable).getTotalElements()).isEqualTo(12);
    }

    @Test
    void preciosPorLitroListarEsPaginadoYVigenteUsaLaReglaDeHoy() {
        PrecioLitroService service = new PrecioLitroService(precioLitroRepository);
        PrecioLitro vigente = new PrecioLitro();
        vigente.setId(UUID.randomUUID());
        vigente.setPrecio(new BigDecimal("1.75"));
        vigente.setVigenteDesde(LocalDate.of(2026, 9, 1));
        when(precioLitroRepository.findAllByOrderByVigenteDesdeDescIdDesc(pageable))
                .thenReturn(new PageImpl<>(List.of(vigente), pageable, 40));
        when(precioLitroRepository.findVigenteHoy()).thenReturn(Optional.of(vigente));

        var pagina = service.listar(pageable);
        assertThat(pagina.getTotalElements()).isEqualTo(40);
        assertThat(pagina.getContent().get(0).precio()).isEqualByComparingTo("1.75");

        assertThat(service.vigente()).hasValueSatisfying(p -> assertThat(p.id()).isEqualTo(vigente.getId()));
    }

    @Test
    void preciosPorLitroVigenteVacioSiNoHayNingunoVigente() {
        PrecioLitroService service = new PrecioLitroService(precioLitroRepository);
        when(precioLitroRepository.findVigenteHoy()).thenReturn(Optional.empty());

        assertThat(service.vigente()).isEmpty();
    }

    @Test
    void usuariosBuscarNormalizaRolYEnviaPatronDeBusqueda() {
        UsuarioService service = new UsuarioService(usuarioRepository, rolRepository, passwordEncoder, refreshTokenService);
        Usuario ana = new Usuario();
        ana.setId(UUID.randomUUID());
        ana.setNombre("Ana");
        ana.setEmail("ana@eco.pe");
        Rol rol = new Rol();
        rol.setNombre("ACOPIADOR");
        ana.setRol(rol);
        ana.setActivo(true);
        when(usuarioRepository.buscarConFiltros("ACOPIADOR", true, "%ana%", pageable))
                .thenReturn(new PageImpl<>(List.of(ana), pageable, 31));

        var pagina = service.buscar(" acopiador ", true, "ANA", pageable);

        assertThat(pagina.getTotalElements()).isEqualTo(31);
        assertThat(pagina.getContent().get(0).rolNombre()).isEqualTo("ACOPIADOR");
    }

    @Test
    void usuariosBuscarSinFiltrosEnviaNulos() {
        UsuarioService service = new UsuarioService(usuarioRepository, rolRepository, passwordEncoder, refreshTokenService);
        when(usuarioRepository.buscarConFiltros(null, null, null, pageable)).thenReturn(new PageImpl<>(List.of(), pageable, 0));

        assertThat(service.buscar("", null, " ", pageable).getContent()).isEmpty();
    }

    @Test
    void busquedaTextoEscapaComodinesYNormaliza() {
        assertThat(BusquedaTexto.patronLike(null)).isNull();
        assertThat(BusquedaTexto.patronLike("   ")).isNull();
        assertThat(BusquedaTexto.patronLike(" Pérez ")).isEqualTo("%perez%"); // sin acentos ni mayúsculas
        assertThat(BusquedaTexto.patronLike("50%_!")).isEqualTo("%50!%!_!!%");
        assertThat(BusquedaTexto.patronLikeOTodos(null)).isEqualTo("%");
        assertThat(BusquedaTexto.patronLikeOTodos("ab")).isEqualTo("%ab%");
    }

    @Test
    void comunicadosListarPliegaLaBusquedaSinAcentosNiMayusculas() {
        ComunicadoService service = new ComunicadoService(comunicadoRepository, zonaRepository, proveedorRepository,
                usuarioRepository, confirmacionRepository);
        when(comunicadoRepository.buscar("%reunion%", pageable)).thenReturn(new PageImpl<>(List.of(), pageable, 1));

        assertThat(service.listar(" Reunión ", pageable).getTotalElements()).isEqualTo(1);
    }
}
