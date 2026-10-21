package com.planta.acopio.service;

import com.planta.acopio.dto.AjusteInsumoRequest;
import com.planta.acopio.dto.IngresoInsumoRequest;
import com.planta.acopio.dto.MovimientoInsumoResponse;
import com.planta.acopio.entity.Insumo;
import com.planta.acopio.entity.LoteProduccion;
import com.planta.acopio.entity.MovimientoInsumo;
import com.planta.acopio.entity.TipoMovimientoInsumo;
import com.planta.acopio.entity.Usuario;
import com.planta.acopio.exception.RecursoInactivoException;
import com.planta.acopio.exception.RecursoNoEncontradoException;
import com.planta.acopio.exception.ValidacionNegocioException;
import com.planta.acopio.repository.InsumoRepository;
import com.planta.acopio.repository.MovimientoInsumoRepository;
import com.planta.acopio.repository.UsuarioRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

// V19: unico punto que cambia el stock de insumos; cada cambio deja su movimiento en el kardex.
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InventarioInsumoServiceTest {

    @Mock private InsumoRepository insumoRepository;
    @Mock private MovimientoInsumoRepository movimientoRepository;
    @Mock private UsuarioRepository usuarioRepository;

    private InventarioInsumoService service;
    private Insumo sal;
    private Usuario usuario;

    @BeforeEach
    void setUp() {
        service = new InventarioInsumoService(insumoRepository, movimientoRepository, usuarioRepository);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("ana@planta.com", null));
        usuario = new Usuario();
        usuario.setId(UUID.randomUUID());
        usuario.setNombre("Ana");
        when(usuarioRepository.findByEmail("ana@planta.com")).thenReturn(Optional.of(usuario));
        sal = insumo("Sal", "6");
        when(movimientoRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private Insumo insumo(String nombre, String stock) {
        Insumo i = new Insumo();
        i.setId(UUID.randomUUID());
        i.setNombre(nombre);
        i.setUnidadMedida("kg");
        i.setActivo(true);
        i.setStock(new BigDecimal(stock));
        when(insumoRepository.findById(i.getId())).thenReturn(Optional.of(i));
        when(insumoRepository.bloquearStock(i.getId())).thenReturn(Optional.of(new BigDecimal(stock)));
        return i;
    }

    private MovimientoInsumo movimientoGuardado() {
        ArgumentCaptor<MovimientoInsumo> c = ArgumentCaptor.forClass(MovimientoInsumo.class);
        verify(movimientoRepository).save(c.capture());
        return c.getValue();
    }

    // ---- Ingreso ----

    @Test
    void ingresoSumaAlStockBajoBloqueoYDejaElMovimientoConUsuarioYReferencia() {
        MovimientoInsumoResponse r = service.registrarIngreso(sal.getId(),
                new IngresoInsumoRequest(new BigDecimal("14"), " Guía 001-55 ", "Compra semanal"));

        InOrder orden = inOrder(insumoRepository, movimientoRepository);
        orden.verify(insumoRepository).bloquearStock(sal.getId());
        orden.verify(insumoRepository).fijarStock(sal.getId(), new BigDecimal("20"));
        orden.verify(movimientoRepository).save(any());
        MovimientoInsumo m = movimientoGuardado();
        assertThat(m.getTipoMovimiento()).isEqualTo(TipoMovimientoInsumo.INGRESO);
        assertThat(m.getCantidad()).isEqualByComparingTo("14");
        assertThat(m.getUsuario()).isSameAs(usuario);
        assertThat(m.getReferencia()).isEqualTo("Guía 001-55");
        assertThat(m.getMotivo()).isEqualTo("Compra semanal");
        assertThat(m.getLoteProduccion()).isNull();
        assertThat(r.saldoAnterior()).isEqualByComparingTo("6");
        assertThat(r.saldoResultante()).isEqualByComparingTo("20");
        assertThat(r.usuarioNombre()).isEqualTo("Ana");
    }

    @Test
    void ingresoRechazaInsumoDesactivadoOInexistenteSinTocarStock() {
        sal.setActivo(false);
        assertThatThrownBy(() -> service.registrarIngreso(sal.getId(), new IngresoInsumoRequest(BigDecimal.ONE, null, null)))
                .isInstanceOf(RecursoInactivoException.class);

        UUID fantasma = UUID.randomUUID();
        when(insumoRepository.findById(fantasma)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.registrarIngreso(fantasma, new IngresoInsumoRequest(BigDecimal.ONE, null, null)))
                .isInstanceOf(RecursoNoEncontradoException.class);
        verify(insumoRepository, never()).fijarStock(any(), any());
        verify(movimientoRepository, never()).save(any());
    }

    // ---- Ajuste por conteo físico ----

    @Test
    void ajusteRegistraLaVariacionContraElStockDelSistemaConMotivoYUsuario() {
        MovimientoInsumoResponse r = service.ajustar(sal.getId(), new AjusteInsumoRequest(new BigDecimal("4.5"), " Merma por humedad "));

        verify(insumoRepository).fijarStock(sal.getId(), new BigDecimal("4.5"));
        MovimientoInsumo m = movimientoGuardado();
        assertThat(m.getTipoMovimiento()).isEqualTo(TipoMovimientoInsumo.AJUSTE);
        assertThat(m.getCantidad()).isEqualByComparingTo("-1.5"); // variación con signo, no el conteo
        assertThat(m.getMotivo()).isEqualTo("Merma por humedad");
        assertThat(m.getUsuario()).isSameAs(usuario);
        assertThat(r.saldoAnterior()).isEqualByComparingTo("6");
        assertThat(r.saldoResultante()).isEqualByComparingTo("4.5");
    }

    @Test
    void ajusteAFavorEsPositivo() {
        service.ajustar(sal.getId(), new AjusteInsumoRequest(new BigDecimal("10"), "Conteo"));
        assertThat(movimientoGuardado().getCantidad()).isEqualByComparingTo("4");
    }

    @Test
    void ajusteSinDiferenciaSeRechazaSinEscribirNada() {
        assertThatThrownBy(() -> service.ajustar(sal.getId(), new AjusteInsumoRequest(new BigDecimal("6.00"), "Conteo")))
                .isInstanceOf(ValidacionNegocioException.class)
                .hasMessageContaining("no hay ninguna diferencia");
        verify(insumoRepository, never()).fijarStock(any(), any());
        verify(movimientoRepository, never()).save(any());
    }

    @Test
    void ajusteSePermiteEnUnInsumoDesactivadoParaLlevarloACero() {
        sal.setActivo(false);
        service.ajustar(sal.getId(), new AjusteInsumoRequest(BigDecimal.ZERO, "Se dejó de usar"));
        verify(insumoRepository).fijarStock(sal.getId(), BigDecimal.ZERO);
    }

    // ---- Salidas por producción ----

    @Test
    void salidasDeProduccionDescuentanEnOrdenDeIdYQuedanLigadasAlLote() {
        Insumo cuajo = insumo("Cuajo", "15");
        LoteProduccion lote = new LoteProduccion();
        lote.setId(UUID.randomUUID());
        when(insumoRepository.descontarStock(any(), any())).thenReturn(1);
        Map<Insumo, BigDecimal> consumo = new LinkedHashMap<>();
        consumo.put(sal, new BigDecimal("5"));
        consumo.put(cuajo, new BigDecimal("10"));

        service.registrarSalidasProduccion(lote, consumo);

        ArgumentCaptor<MovimientoInsumo> c = ArgumentCaptor.forClass(MovimientoInsumo.class);
        verify(movimientoRepository, times(2)).save(c.capture());
        assertThat(c.getAllValues()).allSatisfy(m -> {
            assertThat(m.getTipoMovimiento()).isEqualTo(TipoMovimientoInsumo.SALIDA_PRODUCCION);
            assertThat(m.getLoteProduccion()).isSameAs(lote);
            assertThat(m.getCantidad().signum()).isNegative();
        });
        // Orden estable por id (evita deadlocks entre producciones concurrentes).
        UUID primero = sal.getId().compareTo(cuajo.getId()) < 0 ? sal.getId() : cuajo.getId();
        assertThat(c.getAllValues().get(0).getInsumo().getId()).isEqualTo(primero);
    }

    @Test
    void salidaSinStockSuficienteLanzaYNoDejaMovimientoDeEseInsumo() {
        LoteProduccion lote = new LoteProduccion();
        lote.setId(UUID.randomUUID());
        when(insumoRepository.descontarStock(eq(sal.getId()), any())).thenReturn(0);

        assertThatThrownBy(() -> service.registrarSalidasProduccion(lote, Map.of(sal, new BigDecimal("10"))))
                .isInstanceOf(ValidacionNegocioException.class)
                .hasMessageContaining("Stock insuficiente de Sal");
        verify(movimientoRepository, never()).save(any());
    }

    @Test
    void sinInsumosNoHaceNada() {
        service.registrarSalidasProduccion(new LoteProduccion(), Map.of());
        verifyNoInteractions(movimientoRepository);
    }

    // ---- Saldo inicial ----

    @Test
    void saldoInicialSoloSiHayStock() {
        service.registrarSaldoInicial(sal, BigDecimal.ZERO);
        verify(movimientoRepository, never()).save(any());

        service.registrarSaldoInicial(sal, new BigDecimal("6"));
        MovimientoInsumo m = movimientoGuardado();
        assertThat(m.getTipoMovimiento()).isEqualTo(TipoMovimientoInsumo.SALDO_INICIAL);
        assertThat(m.getCantidad()).isEqualByComparingTo("6");
    }
}
