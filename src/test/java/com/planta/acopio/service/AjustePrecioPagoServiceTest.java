package com.planta.acopio.service;

import com.planta.acopio.dto.AjustePrecioPagoRequest;
import com.planta.acopio.entity.AjustePrecioPago;
import com.planta.acopio.entity.Pago;
import com.planta.acopio.entity.Proveedor;
import com.planta.acopio.entity.Usuario;
import com.planta.acopio.exception.RecursoNoEncontradoException;
import com.planta.acopio.exception.ValidacionNegocioException;
import com.planta.acopio.repository.AjustePrecioPagoRepository;
import com.planta.acopio.repository.PagoRepository;
import com.planta.acopio.repository.UsuarioRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

// El ajuste cambia el precio por litro de UN pago sin tocarlo (inmutable): solo se anexa una fila con precio
// anterior, precio nuevo, motivo y usuario. Ejemplo del negocio: 100 L a S/ 1.00 = S/ 100 -> con 1.20 = S/ 120.
@ExtendWith(MockitoExtension.class)
class AjustePrecioPagoServiceTest {

    @Mock private AjustePrecioPagoRepository repository;
    @Mock private PagoRepository pagoRepository;
    @Mock private UsuarioRepository usuarioRepository;

    private AjustePrecioPagoService service;
    private Pago pago;

    @BeforeEach
    void setUp() {
        service = new AjustePrecioPagoService(repository, pagoRepository, usuarioRepository);
        pago = pagoDe(new BigDecimal("100"), new BigDecimal("1.000"));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin@planta.com", null, List.of()));
    }

    @AfterEach
    void limpiar() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void ajustaElPrecioDeUnPagoYGuardaPrecioAnteriorNuevoMotivoYUsuario() {
        conUsuarioAdmin();
        when(pagoRepository.findById(pago.getId())).thenReturn(Optional.of(pago));
        when(repository.findFirstByPagoIdOrderByCreadoEnDescIdDesc(pago.getId())).thenReturn(Optional.empty());
        when(repository.save(any(AjustePrecioPago.class))).thenAnswer(inv -> inv.getArgument(0));

        var respuesta = service.crear(pago.getId(), new AjustePrecioPagoRequest(new BigDecimal("1.20"), "  Reconocimiento por calidad  "));

        ArgumentCaptor<AjustePrecioPago> captor = ArgumentCaptor.forClass(AjustePrecioPago.class);
        verify(repository).save(captor.capture());
        AjustePrecioPago guardado = captor.getValue();
        assertThat(guardado.getPago()).isSameAs(pago);
        assertThat(guardado.getPrecioLitroAnterior()).isEqualByComparingTo("1.000"); // el del pago original
        assertThat(guardado.getPrecioLitroNuevo()).isEqualByComparingTo("1.20");
        assertThat(guardado.getMotivo()).isEqualTo("Reconocimiento por calidad"); // sin espacios sobrantes
        assertThat(guardado.getUsuario().getEmail()).isEqualTo("admin@planta.com");

        assertThat(respuesta.totalAnterior()).isEqualByComparingTo("100.00");
        assertThat(respuesta.totalNuevo()).isEqualByComparingTo("120.00"); // 100 L x 1.20
        assertThat(respuesta.usuarioNombre()).isEqualTo("Administrador");
    }

    @Test
    void ajustarElPrecioNoModificaElPagoOriginal() {
        conUsuarioAdmin();
        when(pagoRepository.findById(pago.getId())).thenReturn(Optional.of(pago));
        when(repository.findFirstByPagoIdOrderByCreadoEnDescIdDesc(pago.getId())).thenReturn(Optional.empty());
        when(repository.save(any(AjustePrecioPago.class))).thenAnswer(inv -> inv.getArgument(0));

        service.crear(pago.getId(), new AjustePrecioPagoRequest(new BigDecimal("0.80"), "Leche de mala calidad"));

        assertThat(pago.getPrecioLitro()).isEqualByComparingTo("1.000"); // inmutable
        assertThat(pago.getLitrosTotales()).isEqualByComparingTo("100");
        verify(pagoRepository, never()).save(any());
    }

    @Test
    void unSegundoAjusteTomaComoPrecioAnteriorElDelAjustePrevio() {
        conUsuarioAdmin();
        when(pagoRepository.findById(pago.getId())).thenReturn(Optional.of(pago));
        AjustePrecioPago previo = new AjustePrecioPago();
        previo.setPrecioLitroNuevo(new BigDecimal("1.200"));
        when(repository.findFirstByPagoIdOrderByCreadoEnDescIdDesc(pago.getId())).thenReturn(Optional.of(previo));
        when(repository.save(any(AjustePrecioPago.class))).thenAnswer(inv -> inv.getArgument(0));

        var respuesta = service.crear(pago.getId(), new AjustePrecioPagoRequest(new BigDecimal("1.10"), "Corrección"));

        assertThat(respuesta.precioLitroAnterior()).isEqualByComparingTo("1.200"); // no el 1.000 original
        assertThat(respuesta.totalAnterior()).isEqualByComparingTo("120.00");
        assertThat(respuesta.totalNuevo()).isEqualByComparingTo("110.00");
        assertThat(previo.getMotivo()).isNull(); // el ajuste previo no se toca
    }

    @Test
    void rechazaUnPrecioIgualAlActual() {
        when(pagoRepository.findById(pago.getId())).thenReturn(Optional.of(pago));
        when(repository.findFirstByPagoIdOrderByCreadoEnDescIdDesc(pago.getId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.crear(pago.getId(), new AjustePrecioPagoRequest(new BigDecimal("1.0"), "Sin cambio")))
                .isInstanceOf(ValidacionNegocioException.class);

        verify(repository, never()).save(any());
    }

    @Test
    void rechazaPrecioCeroONegativo() {
        when(pagoRepository.findById(pago.getId())).thenReturn(Optional.of(pago));

        assertThatThrownBy(() -> service.crear(pago.getId(), new AjustePrecioPagoRequest(BigDecimal.ZERO, "Motivo")))
                .isInstanceOf(ValidacionNegocioException.class);
        assertThatThrownBy(() -> service.crear(pago.getId(), new AjustePrecioPagoRequest(new BigDecimal("-1"), "Motivo")))
                .isInstanceOf(ValidacionNegocioException.class);

        verify(repository, never()).save(any());
    }

    @Test
    void rechazaMotivoVacioOSoloEspacios() {
        when(pagoRepository.findById(pago.getId())).thenReturn(Optional.of(pago));

        assertThatThrownBy(() -> service.crear(pago.getId(), new AjustePrecioPagoRequest(new BigDecimal("1.20"), "   ")))
                .isInstanceOf(ValidacionNegocioException.class);

        verify(repository, never()).save(any());
    }

    @Test
    void rechazaAjustarUnPagoQueNoExiste() {
        UUID id = UUID.randomUUID();
        when(pagoRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.crear(id, new AjustePrecioPagoRequest(new BigDecimal("1.20"), "Motivo")))
                .isInstanceOf(RecursoNoEncontradoException.class);
    }

    @Test
    void listarPorPagoRechazaSiElPagoNoExiste() {
        UUID id = UUID.randomUUID();
        when(pagoRepository.existsById(id)).thenReturn(false);

        assertThatThrownBy(() -> service.listarPorPago(id)).isInstanceOf(RecursoNoEncontradoException.class);
    }

    @Test
    void listarPorPagoDevuelveElHistorialConLosTotalesRecalculados() {
        when(pagoRepository.existsById(pago.getId())).thenReturn(true);
        AjustePrecioPago a = new AjustePrecioPago();
        a.setId(UUID.randomUUID());
        a.setPago(pago);
        a.setPrecioLitroAnterior(new BigDecimal("1.000"));
        a.setPrecioLitroNuevo(new BigDecimal("0.800"));
        a.setMotivo("Mala calidad");
        a.setUsuario(admin());
        when(repository.findByPagoIdOrderByCreadoEnDescIdDesc(pago.getId())).thenReturn(List.of(a));

        var historial = service.listarPorPago(pago.getId());

        assertThat(historial).hasSize(1);
        assertThat(historial.get(0).totalAnterior()).isEqualByComparingTo("100.00");
        assertThat(historial.get(0).totalNuevo()).isEqualByComparingTo("80.00");
    }

    private void conUsuarioAdmin() {
        when(usuarioRepository.findByEmail("admin@planta.com")).thenReturn(Optional.of(admin()));
    }

    private Usuario admin() {
        Usuario u = new Usuario();
        u.setId(UUID.randomUUID());
        u.setNombre("Administrador");
        u.setEmail("admin@planta.com");
        return u;
    }

    private Pago pagoDe(BigDecimal litros, BigDecimal precio) {
        Pago p = new Pago();
        p.setId(UUID.randomUUID());
        p.setLitrosTotales(litros);
        p.setPrecioLitro(precio);
        Proveedor prov = new Proveedor();
        prov.setId(UUID.randomUUID());
        prov.setNombre("Proveedor Test");
        p.setProveedor(prov);
        return p;
    }
}
