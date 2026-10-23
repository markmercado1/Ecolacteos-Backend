package com.planta.acopio.importacion;

import com.planta.acopio.dto.InsumoRequest;
import com.planta.acopio.repository.InsumoRepository;
import com.planta.acopio.service.InsumoService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ImportacionInsumosTest {

    private InsumoService servicio;
    private InsumoRepository repositorio;
    private ImportacionInsumos tipo;

    @BeforeEach
    void preparar() {
        servicio = mock(InsumoService.class);
        repositorio = mock(InsumoRepository.class);
        when(repositorio.todosLosNombres()).thenReturn(List.of("Sal", "Cuajo Líquido"));
        tipo = new ImportacionInsumos(servicio, repositorio);
    }

    private static CeldaLeida texto(String t) {
        return new CeldaLeida(t, false, false, false);
    }

    private static CeldaLeida numero(String t) {
        return new CeldaLeida(t, true, false, false);
    }

    private ResultadoFila validar(CeldaLeida nombre, CeldaLeida unidad, CeldaLeida stock) {
        Map<String, CeldaLeida> celdas = new LinkedHashMap<>();
        celdas.put("nombre", nombre);
        celdas.put("unidad", unidad);
        celdas.put("stock", stock);
        FilaCruda fila = new FilaCruda(2, celdas);
        return tipo.validar(fila, tipo.preparar(List.of(fila)));
    }

    private ResultadoFila conStock(CeldaLeida stock) {
        return validar(texto("Fermento"), texto("g"), stock);
    }

    @Test
    void unInsumoCompletoEsValidoYElStockSeNormalizaADosDecimales() {
        var r = validar(texto("Fermento"), texto("g"), texto("250.5"));
        assertThat(r.estado()).isEqualTo(EstadoFila.VALIDA);
        assertThat(r.valores()).containsEntry("nombre", "Fermento").containsEntry("unidad", "g").containsEntry("stock", "250.50");
    }

    @Test
    void elStockAceptaComaPuntoNumeroDeExcelYVacioEsCero() {
        assertThat(conStock(texto("1250,75")).valores().get("stock")).isEqualTo("1250.75");
        assertThat(conStock(numero("12")).valores().get("stock")).isEqualTo("12.00");
        assertThat(conStock(numero("0.5")).valores().get("stock")).isEqualTo("0.50");
        assertThat(conStock(CeldaLeida.VACIA).valores().get("stock")).isEqualTo("0.00");
        assertThat(conStock(texto("0")).estado()).isEqualTo(EstadoFila.VALIDA);
        assertThat(conStock(texto("9999999999.99")).estado()).isEqualTo(EstadoFila.VALIDA);
    }

    @Test
    void rechazaStockNegativoConMasDecimalesAmbiguoOTexto() {
        for (String malo : List.of("-5", "12.345", "1,234.50", "1.234,50", "abc", "12 kg", "1e3", "99999999999")) {
            var r = conStock(texto(malo));
            assertThat(r.estado()).as(malo).isEqualTo(EstadoFila.INVALIDA);
            assertThat(r.mensajes().get(0)).as(malo).contains("Stock inicial");
        }
        assertThat(conStock(numero("12.345")).estado()).isEqualTo(EstadoFila.INVALIDA);
    }

    @Test
    void nombreYUnidadSonObligatoriosConSusLongitudes() {
        assertThat(validar(CeldaLeida.VACIA, texto("g"), CeldaLeida.VACIA).mensajes().get(0)).contains("Nombre del insumo: es obligatorio");
        assertThat(validar(texto("x".repeat(61)), texto("g"), CeldaLeida.VACIA).mensajes().get(0)).contains("61 caracteres").contains("60");
        assertThat(validar(texto("Fermento"), CeldaLeida.VACIA, CeldaLeida.VACIA).mensajes().get(0)).contains("Unidad de medida: es obligatorio");
        assertThat(validar(texto("Fermento"), texto("u".repeat(21)), CeldaLeida.VACIA).mensajes().get(0)).contains("21 caracteres").contains("20");
        assertThat(validar(texto("x".repeat(60)), texto("u".repeat(20)), CeldaLeida.VACIA).estado()).isEqualTo(EstadoFila.VALIDA);
    }

    @Test
    void cualquierUnidadSeAceptaLaListaSoloSugiere() {
        assertThat(validar(texto("Fermento"), texto("barricas"), CeldaLeida.VACIA).estado()).isEqualTo(EstadoFila.VALIDA);
        assertThat(tipo.listasAbiertas()).containsExactly("unidad");
        assertThat(tipo.listasDesplegables().get("unidad")).contains("kg", "ml", "unidades");
    }

    @Test
    void unNombreQueYaExisteEsDuplicadoSinDistinguirMayusculasNiAcentos() {
        for (String nombre : List.of("sal", "SAL", "  Sal  ", "cuajo liquido", "CUAJO LÍQUIDO")) {
            var r = validar(texto(nombre), texto("kg"), texto("500"));
            assertThat(r.estado()).as(nombre).isEqualTo(EstadoFila.DUPLICADA);
            assertThat(r.mensajes().get(0)).contains("No se modifica su stock");
        }
    }

    @Test
    void unaFormulaInvalidaLaFila() {
        var r = validar(new CeldaLeida("", false, true, false), texto("kg"), CeldaLeida.VACIA);
        assertThat(r.estado()).isEqualTo(EstadoFila.INVALIDA);
        assertThat(r.mensajes().get(0)).contains("contiene una fórmula");
    }

    @Test
    void creaPorElServicioDeNegocioParaQueElStockInicialQuedeEnElKardex() {
        tipo.crear(Map.of("nombre", "Fermento", "unidad", "g", "stock", "250.50"));

        ArgumentCaptor<InsumoRequest> pedido = ArgumentCaptor.forClass(InsumoRequest.class);
        verify(servicio).crear(pedido.capture());
        assertThat(pedido.getValue()).isEqualTo(new InsumoRequest("Fermento", "g", new BigDecimal("250.50")));
        org.mockito.Mockito.verifyNoMoreInteractions(servicio);
    }

    @Test
    void alConfirmarOmiteSiOtroUsuarioCreoElInsumoEntreTanto() {
        assertThat(tipo.conflictoAlConfirmar(Map.of("nombre", "Fermento"))).isEmpty();
        assertThat(tipo.conflictoAlConfirmar(Map.of("nombre", "SAL"))).hasValueSatisfying(m -> assertThat(m).contains("después de la vista previa"));
    }

    @Test
    void claveUnicaPlegaElNombre() {
        assertThat(tipo.claveUnica(Map.of("nombre", "Cuajo Líquido"))).isEqualTo(tipo.claveUnica(Map.of("nombre", "cuajo liquido")));
        assertThat(Set.of(tipo.codigo())).containsExactly("insumos");
    }
}
