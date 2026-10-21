package com.planta.acopio.service;

import com.planta.acopio.entity.AjustePago;
import com.planta.acopio.entity.CorreccionRegistro;
import com.planta.acopio.entity.EstadoAjustePago;
import com.planta.acopio.entity.Pago;
import com.planta.acopio.entity.RegistroAcopio;
import com.planta.acopio.exception.RecursoNoEncontradoException;
import com.planta.acopio.exception.ValidacionNegocioException;
import com.planta.acopio.repository.AjustePagoRepository;
import com.planta.acopio.repository.PagoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

// Fase 3, decision de negocio 1 (opcion C, confirmada): una correccion
// post-liquidacion NUNCA modifica Pago ni RegistroAcopio (ambos siguen
// inmutables) -- genera un ajuste separado, valorizado SIEMPRE al precio del
// Pago original (no el vigente al momento de la correccion), con liquidacion
// manual (nunca se pliega automaticamente en un pago nuevo).
@ExtendWith(MockitoExtension.class)
class AjustePagoServiceTest {

    @Mock private AjustePagoRepository repository;
    @Mock private PagoRepository pagoRepository;

    private AjustePagoService service;

    @BeforeEach
    void setUp() {
        service = new AjustePagoService(repository, pagoRepository);
    }

    @Test
    void noGeneraAjusteSiElRegistroNoEstabaLiquidado() {
        RegistroAcopio registro = registroCon(BigDecimal.valueOf(40));
        CorreccionRegistro correccion = correccionCon(BigDecimal.valueOf(40), BigDecimal.valueOf(35));
        when(pagoRepository.findByRegistroLiquidadoId(registro.getId())).thenReturn(Optional.empty());

        service.generarSiCorrespondeAjuste(registro, correccion);

        verify(repository, never()).save(any());
    }

    @Test
    void generaAjusteAlPrecioDelPagoOriginalCuandoElRegistroYaEstabaLiquidado() {
        RegistroAcopio registro = registroCon(BigDecimal.valueOf(40));
        CorreccionRegistro correccion = correccionCon(BigDecimal.valueOf(40), BigDecimal.valueOf(35)); // corregido a la baja
        Pago pago = pagoConPrecio(BigDecimal.valueOf(1.8)); // precio de ESE pago, no el "vigente hoy"
        when(pagoRepository.findByRegistroLiquidadoId(registro.getId())).thenReturn(Optional.of(pago));
        // Primera correccion sobre este registro: sin ajuste previo -> el
        // baseline cae al litro original (40), igual que antes del fix.
        when(repository.findFirstByRegistroAcopio_IdOrderByCreadoEnDesc(registro.getId())).thenReturn(Optional.empty());
        when(repository.save(any(AjustePago.class))).thenAnswer(inv -> inv.getArgument(0));

        service.generarSiCorrespondeAjuste(registro, correccion);

        ArgumentCaptor<AjustePago> captor = ArgumentCaptor.forClass(AjustePago.class);
        verify(repository).save(captor.capture());
        AjustePago guardado = captor.getValue();
        assertThat(guardado.getPago()).isEqualTo(pago);
        assertThat(guardado.getRegistroAcopio()).isEqualTo(registro);
        assertThat(guardado.getCorreccionRegistro()).isEqualTo(correccion);
        assertThat(guardado.getLitrosDiferencia()).isEqualByComparingTo(BigDecimal.valueOf(-5)); // 35 - 40
        assertThat(guardado.getPrecioLitro()).isEqualByComparingTo(BigDecimal.valueOf(1.8)); // decision confirmada
        assertThat(guardado.getEstado()).isEqualTo(EstadoAjustePago.PENDIENTE);
    }

    // Fix correcciones multiples (confirmado): una segunda correccion sobre el
    // MISMO registro no debe volver a contar el tramo que el ajuste anterior
    // ya cubrio. Ejemplo confirmado: original 40 -> corregido 35 (ajuste -5,
    // ya generado) -> corregido 30 (este test): el nuevo ajuste debe ser
    // -5 (30-35), NUNCA -10 (30-40) -- suma acumulada -10, nunca -15.
    @Test
    void segundaCorreccionCalculaLaDiferenciaDesdeElUltimoValorCorregidoNoDesdeElOriginal() {
        RegistroAcopio registro = registroCon(BigDecimal.valueOf(40));
        Pago pago = pagoConPrecio(BigDecimal.valueOf(1.8));
        when(pagoRepository.findByRegistroLiquidadoId(registro.getId())).thenReturn(Optional.of(pago));

        AjustePago ajusteAnterior = new AjustePago();
        ajusteAnterior.setCorreccionRegistro(correccionCon(BigDecimal.valueOf(40), BigDecimal.valueOf(35)));
        when(repository.findFirstByRegistroAcopio_IdOrderByCreadoEnDesc(registro.getId()))
                .thenReturn(Optional.of(ajusteAnterior));
        when(repository.save(any(AjustePago.class))).thenAnswer(inv -> inv.getArgument(0));

        // litrosAnterior sigue siendo el original (40) -- CorreccionRegistro no cambia.
        CorreccionRegistro segundaCorreccion = correccionCon(BigDecimal.valueOf(40), BigDecimal.valueOf(30));

        service.generarSiCorrespondeAjuste(registro, segundaCorreccion);

        ArgumentCaptor<AjustePago> captor = ArgumentCaptor.forClass(AjustePago.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getLitrosDiferencia()).isEqualByComparingTo(BigDecimal.valueOf(-5)); // 30 - 35, no 30 - 40
    }

    // Responde directamente la pregunta de diseño: el ajuste anterior no se
    // modifica ni cambia de estado al generarse uno nuevo -- solo se guarda
    // (save) el ajuste nuevo, nunca se vuelve a tocar el anterior.
    @Test
    void noModificaElEstadoDelAjusteAnteriorAlGenerarUnoNuevo() {
        RegistroAcopio registro = registroCon(BigDecimal.valueOf(40));
        Pago pago = pagoConPrecio(BigDecimal.valueOf(1.8));
        when(pagoRepository.findByRegistroLiquidadoId(registro.getId())).thenReturn(Optional.of(pago));

        AjustePago ajusteAnterior = new AjustePago();
        ajusteAnterior.setCorreccionRegistro(correccionCon(BigDecimal.valueOf(40), BigDecimal.valueOf(35)));
        ajusteAnterior.setEstado(EstadoAjustePago.PENDIENTE);
        when(repository.findFirstByRegistroAcopio_IdOrderByCreadoEnDesc(registro.getId()))
                .thenReturn(Optional.of(ajusteAnterior));
        when(repository.save(any(AjustePago.class))).thenAnswer(inv -> inv.getArgument(0));

        service.generarSiCorrespondeAjuste(registro, correccionCon(BigDecimal.valueOf(40), BigDecimal.valueOf(30)));

        assertThat(ajusteAnterior.getEstado()).isEqualTo(EstadoAjustePago.PENDIENTE); // sin cambios
        verify(repository, times(1)).save(any(AjustePago.class)); // solo el nuevo, nunca el anterior
    }

    @Test
    void liquidarMarcaElAjusteComoLiquidado() {
        AjustePago ajuste = ajusteConEstado(EstadoAjustePago.PENDIENTE);
        when(repository.findById(ajuste.getId())).thenReturn(Optional.of(ajuste));

        var resultado = service.liquidar(ajuste.getId());

        assertThat(resultado.estado()).isEqualTo("LIQUIDADO");
    }

    @Test
    void rechazaLiquidarUnAjusteQueYaEstabaLiquidado() {
        AjustePago ajuste = ajusteConEstado(EstadoAjustePago.LIQUIDADO);
        when(repository.findById(ajuste.getId())).thenReturn(Optional.of(ajuste));

        assertThatThrownBy(() -> service.liquidar(ajuste.getId()))
                .isInstanceOf(ValidacionNegocioException.class);
    }

    @Test
    void listarPorPagoRechazaSiElPagoNoExiste() {
        UUID pagoId = UUID.randomUUID();
        when(pagoRepository.existsById(pagoId)).thenReturn(false);

        assertThatThrownBy(() -> service.listarPorPago(pagoId))
                .isInstanceOf(RecursoNoEncontradoException.class);
    }

    private RegistroAcopio registroCon(BigDecimal litros) {
        RegistroAcopio r = new RegistroAcopio();
        r.setId(UUID.randomUUID());
        r.setLitros(litros);
        return r;
    }

    private CorreccionRegistro correccionCon(BigDecimal anterior, BigDecimal corregido) {
        CorreccionRegistro c = new CorreccionRegistro();
        c.setId(UUID.randomUUID());
        c.setLitrosAnterior(anterior);
        c.setLitrosCorregido(corregido);
        return c;
    }

    private Pago pagoConPrecio(BigDecimal precio) {
        Pago p = new Pago();
        p.setId(UUID.randomUUID());
        p.setPrecioLitro(precio);
        return p;
    }

    private AjustePago ajusteConEstado(EstadoAjustePago estado) {
        AjustePago a = new AjustePago();
        a.setId(UUID.randomUUID());
        a.setPago(pagoConPrecio(BigDecimal.valueOf(1.8)));
        a.setRegistroAcopio(registroCon(BigDecimal.valueOf(40)));
        a.setCorreccionRegistro(correccionCon(BigDecimal.valueOf(40), BigDecimal.valueOf(35)));
        a.setLitrosDiferencia(BigDecimal.valueOf(-5));
        a.setPrecioLitro(BigDecimal.valueOf(1.8));
        a.setEstado(estado);
        return a;
    }
}
