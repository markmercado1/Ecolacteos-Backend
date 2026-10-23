package com.planta.acopio.service;

import com.planta.acopio.dto.AjusteCalidadEntregaRequest;
import com.planta.acopio.entity.AjusteCalidadEntrega;
import com.planta.acopio.entity.AnalisisCalidad;
import com.planta.acopio.entity.LineaPago;
import com.planta.acopio.entity.Pago;
import com.planta.acopio.entity.RegistroAcopio;
import com.planta.acopio.entity.ResultadoCalidad;
import com.planta.acopio.entity.Usuario;
import com.planta.acopio.exception.RecursoNoEncontradoException;
import com.planta.acopio.exception.ValidacionNegocioException;
import com.planta.acopio.repository.AjusteCalidadEntregaRepository;
import com.planta.acopio.repository.AnalisisCalidadRepository;
import com.planta.acopio.repository.LineaPagoRepository;
import com.planta.acopio.repository.PagoRepository;
import com.planta.acopio.repository.UsuarioRepository;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

// Fase C: precio de calidad de UNA entrega de un pago, a mano, con motivo y el analisis de esa misma entrega. Append-only.
@ExtendWith(MockitoExtension.class)
class AjusteCalidadEntregaServiceTest {

    @Mock private AjusteCalidadEntregaRepository repository;
    @Mock private LineaPagoRepository lineaPagoRepository;
    @Mock private AnalisisCalidadRepository analisisRepository;
    @Mock private PagoRepository pagoRepository;
    @Mock private UsuarioRepository usuarioRepository;
    @Mock private PrecioAplicado precioAplicado;

    private AjusteCalidadEntregaService service;

    private final UUID pagoId = UUID.randomUUID();
    private RegistroAcopio entrega;
    private LineaPago linea;
    private AnalisisCalidad analisis;

    @BeforeEach
    void setUp() {
        service = new AjusteCalidadEntregaService(repository, lineaPagoRepository, analisisRepository, pagoRepository, usuarioRepository, precioAplicado);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("admin@planta.com", null));

        Pago pago = new Pago();
        pago.setId(pagoId);
        entrega = new RegistroAcopio();
        entrega.setId(UUID.randomUUID());
        linea = new LineaPago();
        linea.setId(UUID.randomUUID());
        linea.setPago(pago);
        linea.setRegistroAcopio(entrega);
        linea.setLitrosLiquidados(new BigDecimal("100.00"));
        linea.setPrecioBase(new BigDecimal("1.000"));
        analisis = new AnalisisCalidad();
        analisis.setId(UUID.randomUUID());
        analisis.setRegistroAcopio(entrega);
        analisis.setFolioMuestra("F-001");
        analisis.setResultado(ResultadoCalidad.OBSERVADO);
    }

    private AjusteCalidadEntregaRequest solicitud(String precio, String motivo) {
        return new AjusteCalidadEntregaRequest(new BigDecimal(precio), analisis.getId(), motivo);
    }

    private void preparar(String precioActual) {
        when(lineaPagoRepository.findByPagoIdAndRegistroParaActualizar(pagoId, entrega.getId())).thenReturn(Optional.of(linea));
        when(analisisRepository.findById(analisis.getId())).thenReturn(Optional.of(analisis));
        when(precioAplicado.deLinea(linea)).thenReturn(new BigDecimal(precioActual));
    }

    private void conUsuario() {
        Usuario admin = new Usuario();
        admin.setId(UUID.randomUUID());
        admin.setNombre("Administrador");
        when(usuarioRepository.findByEmail("admin@planta.com")).thenReturn(Optional.of(admin));
        when(repository.save(any(AjusteCalidadEntrega.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void registraElPrecioConSuAnalisisMotivoYUsuarioYElPrecioAnteriorEsElAplicado() {
        preparar("1.000");
        conUsuario();

        var respuesta = service.crear(pagoId, entrega.getId(), solicitud("0.900", "  Agua en la leche  "));

        ArgumentCaptor<AjusteCalidadEntrega> captor = ArgumentCaptor.forClass(AjusteCalidadEntrega.class);
        verify(repository).save(captor.capture());
        AjusteCalidadEntrega guardado = captor.getValue();
        assertThat(guardado.getPagoLinea()).isSameAs(linea);
        assertThat(guardado.getPrecioAnterior()).isEqualByComparingTo("1.000");
        assertThat(guardado.getPrecioNuevo()).isEqualByComparingTo("0.900");
        assertThat(guardado.getAnalisisCalidad()).isSameAs(analisis);
        assertThat(guardado.getMotivo()).isEqualTo("Agua en la leche"); // recortado
        assertThat(guardado.getUsuario().getNombre()).isEqualTo("Administrador");
        // 100 L x (0.90 - 1.00) = -10.00: se paga menos por esta entrega
        assertThat(respuesta.efectoEnElTotal()).isEqualByComparingTo("-10.00");
        assertThat(respuesta.analisisFolio()).isEqualTo("F-001");
        assertThat(respuesta.analisisResultado()).isEqualTo("OBSERVADO");
    }

    @Test
    void elPrecioAnteriorDeUnSegundoAjusteEsElDelPrimero() {
        preparar("0.900"); // la entrega ya tenia 0.90 por un ajuste anterior
        conUsuario();

        service.crear(pagoId, entrega.getId(), solicitud("0.800", "Segundo analisis"));

        ArgumentCaptor<AjusteCalidadEntrega> captor = ArgumentCaptor.forClass(AjusteCalidadEntrega.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getPrecioAnterior()).isEqualByComparingTo("0.900");
    }

    @Test
    void rechazaUnMotivoVacioSinLeerNada() {
        assertThatThrownBy(() -> service.crear(pagoId, entrega.getId(), solicitud("0.900", "   ")))
                .isInstanceOf(ValidacionNegocioException.class);

        verifyNoInteractions(lineaPagoRepository, analisisRepository);
        verify(repository, never()).save(any());
    }

    @Test
    void rechazaUnPrecioNoPositivo() {
        assertThatThrownBy(() -> service.crear(pagoId, entrega.getId(), solicitud("0", "Motivo")))
                .isInstanceOf(ValidacionNegocioException.class);

        verify(repository, never()).save(any());
    }

    @Test
    void rechazaUnaEntregaQueNoEstaLiquidadaEnEsePago() {
        when(lineaPagoRepository.findByPagoIdAndRegistroParaActualizar(pagoId, entrega.getId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.crear(pagoId, entrega.getId(), solicitud("0.900", "Motivo")))
                .isInstanceOf(RecursoNoEncontradoException.class);

        verify(repository, never()).save(any());
    }

    @Test
    void rechazaUnAnalisisInexistente() {
        when(lineaPagoRepository.findByPagoIdAndRegistroParaActualizar(pagoId, entrega.getId())).thenReturn(Optional.of(linea));
        when(analisisRepository.findById(analisis.getId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.crear(pagoId, entrega.getId(), solicitud("0.900", "Motivo")))
                .isInstanceOf(RecursoNoEncontradoException.class);
    }

    @Test
    void rechazaUnAnalisisDeOtraEntrega() {
        RegistroAcopio otra = new RegistroAcopio();
        otra.setId(UUID.randomUUID());
        analisis.setRegistroAcopio(otra);
        when(lineaPagoRepository.findByPagoIdAndRegistroParaActualizar(pagoId, entrega.getId())).thenReturn(Optional.of(linea));
        when(analisisRepository.findById(analisis.getId())).thenReturn(Optional.of(analisis));

        assertThatThrownBy(() -> service.crear(pagoId, entrega.getId(), solicitud("0.900", "Motivo")))
                .isInstanceOf(ValidacionNegocioException.class)
                .hasMessageContaining("no corresponde a esta entrega");

        verify(repository, never()).save(any());
    }

    @Test
    void rechazaUnPrecioIgualAlAplicadoYTambienSiSoloCambiaLaEscala() {
        preparar("0.900");

        assertThatThrownBy(() -> service.crear(pagoId, entrega.getId(), solicitud("0.9", "Motivo")))
                .isInstanceOf(ValidacionNegocioException.class);

        verify(repository, never()).save(any());
    }

    @Test
    void elHistorialDeUnPagoInexistenteDa404() {
        when(pagoRepository.existsById(pagoId)).thenReturn(false);

        assertThatThrownBy(() -> service.listarPorPago(pagoId)).isInstanceOf(RecursoNoEncontradoException.class);
    }

    @Test
    void elHistorialListaLosAjustesDelPago() {
        when(pagoRepository.existsById(pagoId)).thenReturn(true);
        AjusteCalidadEntrega a = new AjusteCalidadEntrega();
        a.setId(UUID.randomUUID());
        a.setPagoLinea(linea);
        a.setPrecioAnterior(new BigDecimal("1.000"));
        a.setPrecioNuevo(new BigDecimal("0.900"));
        a.setAnalisisCalidad(analisis);
        a.setMotivo("Agua");
        Usuario admin = new Usuario();
        admin.setNombre("Administrador");
        a.setUsuario(admin);
        when(repository.findByPagoLineaPagoIdOrderByCreadoEnDescIdDesc(pagoId)).thenReturn(List.of(a));

        var lista = service.listarPorPago(pagoId);

        assertThat(lista).hasSize(1);
        assertThat(lista.get(0).registroAcopioId()).isEqualTo(entrega.getId());
        assertThat(lista.get(0).pagoId()).isEqualTo(pagoId);
    }
}
