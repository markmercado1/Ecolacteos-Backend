package com.planta.acopio.service;

import com.planta.acopio.entity.AjustePago;
import com.planta.acopio.entity.CorreccionRegistro;
import com.planta.acopio.entity.EstadoAjustePago;
import com.planta.acopio.entity.LineaPago;
import com.planta.acopio.entity.Pago;
import com.planta.acopio.entity.RegistroAcopio;
import com.planta.acopio.exception.RecursoNoEncontradoException;
import com.planta.acopio.exception.ValidacionNegocioException;
import com.planta.acopio.repository.AjustePagoRepository;
import com.planta.acopio.repository.LineaPagoRepository;
import com.planta.acopio.repository.PagoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// Fase 3, decision de negocio 1 (opcion C, confirmada): una correccion post-liquidacion NUNCA modifica Pago ni RegistroAcopio
// (ambos siguen inmutables) -- genera un ajuste separado, con liquidacion manual (nunca se pliega automaticamente en un pago nuevo).
//
// Fase B: el ajuste parte de los litros CONTABILIZADOS de la entrega (liquidados + ajustes previos) y se valora al precio
// efectivamente aplicado (incluye los ajustes de calidad del pago); PENDIENTE sigue al precio del pago, LIQUIDADO queda congelado.
@ExtendWith(MockitoExtension.class)
class AjustePagoServiceTest {

    @Mock private AjustePagoRepository repository;
    @Mock private PagoRepository pagoRepository;
    @Mock private LineaPagoRepository lineaPagoRepository;
    @Mock private PrecioAplicado precioAplicado;

    private AjustePagoService service;
    private final java.util.Map<UUID, LineaPago> lineaDe = new java.util.HashMap<>();

    @BeforeEach
    void setUp() {
        service = new AjustePagoService(repository, pagoRepository, lineaPagoRepository, precioAplicado);
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
    void generaAjusteContraLosLitrosLiquidadosAlPrecioEfectivoDelPago() {
        RegistroAcopio registro = registroCon(BigDecimal.valueOf(40));
        Pago pago = pagoConPrecio(BigDecimal.valueOf(1.8));
        when(pagoRepository.findByRegistroLiquidadoId(registro.getId())).thenReturn(Optional.of(pago));
        liquidada(registro, pago, 40, 40);
        when(repository.sumaLitrosDiferenciaDelRegistro(registro.getId())).thenReturn(BigDecimal.ZERO);
        when(precioAplicado.deLinea(lineaDe.get(registro.getId()))).thenReturn(BigDecimal.valueOf(1.8));
        when(repository.save(any(AjustePago.class))).thenAnswer(inv -> inv.getArgument(0));

        service.generarSiCorrespondeAjuste(registro, correccionCon(BigDecimal.valueOf(40), BigDecimal.valueOf(35)));

        AjustePago guardado = guardado();
        assertThat(guardado.getPago()).isEqualTo(pago);
        assertThat(guardado.getRegistroAcopio()).isEqualTo(registro);
        assertThat(guardado.getLitrosContabilizadosAntes()).isEqualByComparingTo("40");
        assertThat(guardado.getLitrosDiferencia()).isEqualByComparingTo("-5"); // 35 - 40
        assertThat(guardado.getPrecioLitro()).isEqualByComparingTo("1.8");
        assertThat(guardado.getEstado()).isEqualTo(EstadoAjustePago.PENDIENTE);
    }

    // El caso que antes se perdia: original 100, corregido a 90 ANTES de liquidar (se liquidaron 90). Una correccion posterior a 85
    // ajusta -5 respecto de lo liquidado, no -15 respecto del original.
    @Test
    void conUnaCorreccionPreviaALiquidarElAjusteParteDeLosLitrosLiquidadosNoDelOriginal() {
        RegistroAcopio registro = registroCon(BigDecimal.valueOf(100));
        Pago pago = pagoConPrecio(BigDecimal.ONE);
        when(pagoRepository.findByRegistroLiquidadoId(registro.getId())).thenReturn(Optional.of(pago));
        liquidada(registro, pago, 100, 90);
        when(repository.sumaLitrosDiferenciaDelRegistro(registro.getId())).thenReturn(BigDecimal.ZERO);
        when(precioAplicado.deLinea(lineaDe.get(registro.getId()))).thenReturn(BigDecimal.ONE);
        when(repository.save(any(AjustePago.class))).thenAnswer(inv -> inv.getArgument(0));

        service.generarSiCorrespondeAjuste(registro, correccionCon(BigDecimal.valueOf(100), BigDecimal.valueOf(85)));

        assertThat(guardado().getLitrosContabilizadosAntes()).isEqualByComparingTo("90");
        assertThat(guardado().getLitrosDiferencia()).isEqualByComparingTo("-5");
    }

    // Segunda correccion: original 40, liquidados 40, ya hubo un ajuste de -5 (35). Corregir a 30 suma -5 mas (nunca -10 ni -15).
    @Test
    void segundaCorreccionParteDeLosLitrosYaContabilizadosIncluidosLosAjustesPrevios() {
        RegistroAcopio registro = registroCon(BigDecimal.valueOf(40));
        Pago pago = pagoConPrecio(BigDecimal.valueOf(1.8));
        when(pagoRepository.findByRegistroLiquidadoId(registro.getId())).thenReturn(Optional.of(pago));
        liquidada(registro, pago, 40, 40);
        when(repository.sumaLitrosDiferenciaDelRegistro(registro.getId())).thenReturn(BigDecimal.valueOf(-5));
        when(precioAplicado.deLinea(lineaDe.get(registro.getId()))).thenReturn(BigDecimal.valueOf(1.8));
        when(repository.save(any(AjustePago.class))).thenAnswer(inv -> inv.getArgument(0));

        service.generarSiCorrespondeAjuste(registro, correccionCon(BigDecimal.valueOf(40), BigDecimal.valueOf(30)));

        assertThat(guardado().getLitrosContabilizadosAntes()).isEqualByComparingTo("35");
        assertThat(guardado().getLitrosDiferencia()).isEqualByComparingTo("-5"); // 30 - 35
    }

    @Test
    void elAjusteSeCreaAlPrecioEfectivoQueIncluyeLosAjustesDeCalidadDelPago() {
        RegistroAcopio registro = registroCon(BigDecimal.valueOf(90));
        Pago pago = pagoConPrecio(BigDecimal.ONE);
        when(pagoRepository.findByRegistroLiquidadoId(registro.getId())).thenReturn(Optional.of(pago));
        liquidada(registro, pago, 90, 90);
        when(repository.sumaLitrosDiferenciaDelRegistro(registro.getId())).thenReturn(BigDecimal.ZERO);
        when(precioAplicado.deLinea(lineaDe.get(registro.getId()))).thenReturn(new BigDecimal("0.900")); // el pago tiene un ajuste de calidad 1.00 -> 0.90
        when(repository.save(any(AjustePago.class))).thenAnswer(inv -> inv.getArgument(0));

        service.generarSiCorrespondeAjuste(registro, correccionCon(BigDecimal.valueOf(90), BigDecimal.valueOf(85)));

        assertThat(guardado().getPrecioLitro()).isEqualByComparingTo("0.90");
    }

    @Test
    void noGeneraAjusteSiLaCorreccionNoCambiaLoContabilizado() {
        RegistroAcopio registro = registroCon(BigDecimal.valueOf(40));
        Pago pago = pagoConPrecio(BigDecimal.ONE);
        when(pagoRepository.findByRegistroLiquidadoId(registro.getId())).thenReturn(Optional.of(pago));
        liquidada(registro, pago, 40, 40);
        when(repository.sumaLitrosDiferenciaDelRegistro(registro.getId())).thenReturn(BigDecimal.ZERO);

        service.generarSiCorrespondeAjuste(registro, correccionCon(BigDecimal.valueOf(40), BigDecimal.valueOf(40)));

        verify(repository, never()).save(any());
    }

    // Respaldo: un pago sin linea (no deberia existir tras V28) conserva el criterio anterior.
    @Test
    void sinLineaDePagoConservaElCriterioAnteriorDelUltimoAjuste() {
        RegistroAcopio registro = registroCon(BigDecimal.valueOf(40));
        Pago pago = pagoConPrecio(BigDecimal.ONE);
        when(pagoRepository.findByRegistroLiquidadoId(registro.getId())).thenReturn(Optional.of(pago));
        when(lineaPagoRepository.findByRegistroAcopioId(registro.getId())).thenReturn(Optional.empty());
        AjustePago anterior = new AjustePago();
        anterior.setCorreccionRegistro(correccionCon(BigDecimal.valueOf(40), BigDecimal.valueOf(35)));
        when(repository.findFirstByRegistroAcopio_IdOrderByCreadoEnDesc(registro.getId())).thenReturn(Optional.of(anterior));
        when(precioAplicado.efectivoDelPago(pago)).thenReturn(BigDecimal.ONE);
        when(repository.save(any(AjustePago.class))).thenAnswer(inv -> inv.getArgument(0));

        service.generarSiCorrespondeAjuste(registro, correccionCon(BigDecimal.valueOf(40), BigDecimal.valueOf(30)));

        assertThat(guardado().getLitrosDiferencia()).isEqualByComparingTo("-5"); // 30 - 35
    }

    // Responde la pregunta de diseño: el ajuste anterior no se modifica al generarse uno nuevo.
    @Test
    void noModificaElEstadoDelAjusteAnteriorAlGenerarUnoNuevo() {
        RegistroAcopio registro = registroCon(BigDecimal.valueOf(40));
        Pago pago = pagoConPrecio(BigDecimal.ONE);
        when(pagoRepository.findByRegistroLiquidadoId(registro.getId())).thenReturn(Optional.of(pago));
        liquidada(registro, pago, 40, 40);
        when(repository.sumaLitrosDiferenciaDelRegistro(registro.getId())).thenReturn(BigDecimal.valueOf(-5));
        when(precioAplicado.deLinea(lineaDe.get(registro.getId()))).thenReturn(BigDecimal.ONE);
        when(repository.save(any(AjustePago.class))).thenAnswer(inv -> inv.getArgument(0));

        service.generarSiCorrespondeAjuste(registro, correccionCon(BigDecimal.valueOf(40), BigDecimal.valueOf(30)));

        verify(repository, times(1)).save(any(AjustePago.class)); // solo el nuevo, nunca el anterior
    }

    // Ejemplo del negocio: ajuste de -5 L; el pago tiene un ajuste de calidad 1.00 -> 0.90. Pendiente: -5 x 0.90 = -4.50 (no -5.00).
    @Test
    void unAjustePendienteSeValoraAlPrecioEfectivoActualDelPago() {
        Pago pago = pagoConPrecio(BigDecimal.ONE);
        AjustePago ajuste = ajusteConEstado(pago, EstadoAjustePago.PENDIENTE, BigDecimal.ONE);
        when(pagoRepository.findById(pago.getId())).thenReturn(Optional.of(pago));
        when(precioAplicado.efectivosDePagos(any())).thenReturn(Map.of(pago.getId(), new BigDecimal("0.900")));
        when(repository.findByPagoIdOrderByCreadoEnDesc(pago.getId())).thenReturn(List.of(ajuste));

        var respuesta = service.listarPorPago(pago.getId()).get(0);

        assertThat(respuesta.precioLitro()).isEqualByComparingTo("0.90");
        assertThat(respuesta.monto()).isEqualByComparingTo("-4.50");
        assertThat(respuesta.precioLitroAlCrear()).isEqualByComparingTo("1.00"); // lo que valia cuando se creo
    }

    @Test
    void unAjusteLiquidadoConservaElPrecioCongelado() {
        Pago pago = pagoConPrecio(BigDecimal.ONE);
        AjustePago ajuste = ajusteConEstado(pago, EstadoAjustePago.LIQUIDADO, BigDecimal.ONE);
        ajuste.setPrecioLitroLiquidado(BigDecimal.ONE);
        when(pagoRepository.findById(pago.getId())).thenReturn(Optional.of(pago));
        when(precioAplicado.efectivosDePagos(any())).thenReturn(Map.of(pago.getId(), new BigDecimal("0.800"))); // el pago bajo despues
        when(repository.findByPagoIdOrderByCreadoEnDesc(pago.getId())).thenReturn(List.of(ajuste));

        var respuesta = service.listarPorPago(pago.getId()).get(0);

        assertThat(respuesta.monto()).isEqualByComparingTo("-5.00"); // no cambia: ya se salda a 1.00
    }

    @Test
    void liquidarCongelaElPrecioEfectivoDelMomentoYMarcaElAjusteComoLiquidado() {
        Pago pago = pagoConPrecio(BigDecimal.ONE);
        AjustePago ajuste = ajusteConEstado(pago, EstadoAjustePago.PENDIENTE, BigDecimal.ONE);
        when(repository.findById(ajuste.getId())).thenReturn(Optional.of(ajuste));
        when(precioAplicado.efectivoDelPago(pago)).thenReturn(new BigDecimal("0.900"));

        var resultado = service.liquidar(ajuste.getId());

        assertThat(resultado.estado()).isEqualTo("LIQUIDADO");
        assertThat(ajuste.getPrecioLitroLiquidado()).isEqualByComparingTo("0.90");
        assertThat(resultado.monto()).isEqualByComparingTo("-4.50");
    }

    @Test
    void rechazaLiquidarUnAjusteQueYaEstabaLiquidado() {
        AjustePago ajuste = ajusteConEstado(pagoConPrecio(BigDecimal.ONE), EstadoAjustePago.LIQUIDADO, BigDecimal.ONE);
        when(repository.findById(ajuste.getId())).thenReturn(Optional.of(ajuste));

        assertThatThrownBy(() -> service.liquidar(ajuste.getId()))
                .isInstanceOf(ValidacionNegocioException.class);
    }

    @Test
    void listarPorPagoRechazaSiElPagoNoExiste() {
        UUID pagoId = UUID.randomUUID();
        when(pagoRepository.findById(pagoId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.listarPorPago(pagoId))
                .isInstanceOf(RecursoNoEncontradoException.class);
    }

    private AjustePago guardado() {
        ArgumentCaptor<AjustePago> captor = ArgumentCaptor.forClass(AjustePago.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }

    private void liquidada(RegistroAcopio registro, Pago pago, int originales, int liquidados) {
        LineaPago linea = new LineaPago();
        linea.setId(UUID.randomUUID());
        linea.setPago(pago);
        linea.setRegistroAcopio(registro);
        linea.setLitrosOriginales(BigDecimal.valueOf(originales));
        linea.setLitrosLiquidados(BigDecimal.valueOf(liquidados));
        when(lineaPagoRepository.findByRegistroAcopioId(registro.getId())).thenReturn(Optional.of(linea));
        lineaDe.put(registro.getId(), linea);
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

    private AjustePago ajusteConEstado(Pago pago, EstadoAjustePago estado, BigDecimal precioAlCrear) {
        AjustePago a = new AjustePago();
        a.setId(UUID.randomUUID());
        a.setPago(pago);
        a.setRegistroAcopio(registroCon(BigDecimal.valueOf(40)));
        a.setCorreccionRegistro(correccionCon(BigDecimal.valueOf(40), BigDecimal.valueOf(35)));
        a.setLitrosDiferencia(BigDecimal.valueOf(-5));
        a.setPrecioLitro(precioAlCrear);
        a.setEstado(estado);
        return a;
    }
}
