package com.planta.acopio.service;

import com.planta.acopio.dto.AjusteInventarioRequest;
import com.planta.acopio.entity.AjusteInventario;
import com.planta.acopio.entity.Presentacion;
import com.planta.acopio.entity.Producto;
import com.planta.acopio.entity.Usuario;
import com.planta.acopio.exception.RecursoNoEncontradoException;
import com.planta.acopio.exception.ValidacionNegocioException;
import com.planta.acopio.repository.AjusteInventarioRepository;
import com.planta.acopio.repository.MovimientoInventarioRepository;
import com.planta.acopio.repository.PresentacionRepository;
import com.planta.acopio.repository.UsuarioRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// Inventario: accion "Ajustar stock" (V14__ajuste_inventario.sql). Append-only, mismo criterio que
// CorreccionRegistroService: cantidadAnterior es siempre la que tenia el sistema al momento del ajuste
// (leida bajo el mismo bloqueo pesimista que usa validarStockSuficiente), nunca un valor recalculado despues.
@ExtendWith(MockitoExtension.class)
class AjusteInventarioServiceTest {

    @Mock private AjusteInventarioRepository repository;
    @Mock private PresentacionRepository presentacionRepository;
    @Mock private MovimientoInventarioRepository movimientoRepository;
    @Mock private UsuarioRepository usuarioRepository;
    @Mock private InventarioService inventarioService;

    private AjusteInventarioService service;
    private final UUID presentacionId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new AjusteInventarioService(repository, presentacionRepository, movimientoRepository, usuarioRepository, inventarioService);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin@planta.com", null));
    }

    private Presentacion botella1L() {
        Producto producto = new Producto();
        producto.setNombre("Yogurt de fresa");
        Presentacion p = new Presentacion();
        p.setId(presentacionId);
        p.setNombre("Botella 1 L");
        p.setProducto(producto);
        return p;
    }

    private Usuario admin() {
        Usuario u = new Usuario();
        u.setId(UUID.randomUUID());
        u.setNombre("Ana Admin");
        u.setEmail("admin@planta.com");
        return u;
    }

    @Test
    void registraUnAjusteConDeltaNegativoCuandoElConteoFisicoEsMenorQueElSistema() {
        when(presentacionRepository.buscarConBloqueoParaActualizarStock(presentacionId)).thenReturn(Optional.of(botella1L()));
        when(movimientoRepository.sumarCantidadPorPresentacion(presentacionId)).thenReturn(100L);
        when(usuarioRepository.findByEmail("admin@planta.com")).thenReturn(Optional.of(admin()));
        when(repository.save(any())).thenAnswer(inv -> {
            AjusteInventario a = inv.getArgument(0);
            a.setId(UUID.randomUUID());
            return a;
        });

        var dto = new AjusteInventarioRequest(presentacionId, 95, "Conteo físico mensual: faltan 5 unidades.");

        var resultado = service.crear(dto);

        assertThat(resultado.cantidadAnterior()).isEqualTo(100);
        assertThat(resultado.cantidadCorregida()).isEqualTo(95);
        assertThat(resultado.delta()).isEqualTo(-5);
        assertThat(resultado.usuarioNombre()).isEqualTo("Ana Admin");

        ArgumentCaptor<AjusteInventario> ajusteCaptor = ArgumentCaptor.forClass(AjusteInventario.class);
        verify(inventarioService).registrarAjuste(ajusteCaptor.capture(), org.mockito.ArgumentMatchers.eq(-5));
        assertThat(ajusteCaptor.getValue().getCantidadAnterior()).isEqualTo(100);
        assertThat(ajusteCaptor.getValue().getCantidadCorregida()).isEqualTo(95);
    }

    @Test
    void registraUnAjusteConDeltaPositivoCuandoElConteoFisicoEsMayorQueElSistema() {
        when(presentacionRepository.buscarConBloqueoParaActualizarStock(presentacionId)).thenReturn(Optional.of(botella1L()));
        when(movimientoRepository.sumarCantidadPorPresentacion(presentacionId)).thenReturn(50L);
        when(usuarioRepository.findByEmail("admin@planta.com")).thenReturn(Optional.of(admin()));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var dto = new AjusteInventarioRequest(presentacionId, 60, "Se encontraron unidades sin registrar.");

        var resultado = service.crear(dto);

        assertThat(resultado.delta()).isEqualTo(10);
        verify(inventarioService).registrarAjuste(any(), org.mockito.ArgumentMatchers.eq(10));
    }

    @Test
    void rechazaSiElConteoFisicoCoincideConElStockDelSistemaYNoRegistraNada() {
        when(presentacionRepository.buscarConBloqueoParaActualizarStock(presentacionId)).thenReturn(Optional.of(botella1L()));
        when(movimientoRepository.sumarCantidadPorPresentacion(presentacionId)).thenReturn(100L);

        var dto = new AjusteInventarioRequest(presentacionId, 100, "Conteo de rutina");

        assertThatThrownBy(() -> service.crear(dto))
                .isInstanceOf(ValidacionNegocioException.class)
                .hasMessageContaining("no hay ninguna diferencia");

        verify(repository, never()).save(any());
        verify(inventarioService, never()).registrarAjuste(any(), org.mockito.ArgumentMatchers.anyInt());
        verify(usuarioRepository, never()).findByEmail(any());
    }

    @Test
    void fallaSiLaPresentacionNoExiste() {
        when(presentacionRepository.buscarConBloqueoParaActualizarStock(presentacionId)).thenReturn(Optional.empty());

        var dto = new AjusteInventarioRequest(presentacionId, 10, "motivo");

        assertThatThrownBy(() -> service.crear(dto)).isInstanceOf(RecursoNoEncontradoException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void listarPorPresentacionCalculaElDeltaDeCadaAjusteHistorico() {
        AjusteInventario a1 = new AjusteInventario();
        a1.setId(UUID.randomUUID());
        a1.setPresentacion(botella1L());
        a1.setUsuario(admin());
        a1.setCantidadAnterior(100);
        a1.setCantidadCorregida(95);
        a1.setMotivo("Rotura");
        var pageable = org.springframework.data.domain.PageRequest.of(1, 1);
        when(repository.findByPresentacionIdOrderByCreadoEnDescIdDesc(presentacionId, pageable))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(a1), pageable, 3));

        var resultado = service.listarPorPresentacion(presentacionId, pageable);

        assertThat(resultado.getContent()).hasSize(1);
        assertThat(resultado.getContent().get(0).delta()).isEqualTo(-5);
        // El total viene de la BD (count), no del tamaño de la página devuelta.
        assertThat(resultado.getTotalElements()).isEqualTo(3);
        assertThat(resultado.getNumber()).isEqualTo(1);
    }
}
