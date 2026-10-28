package com.planta.acopio.service;

import com.planta.acopio.entity.ReglaComprador;
import com.planta.acopio.entity.TipoPrecio;
import java.util.Set;
import static org.mockito.Mockito.when;
import com.planta.acopio.entity.TipoComprador;
import com.planta.acopio.entity.TipoPersona;
import com.planta.acopio.exception.ValidacionNegocioException;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

// Fase H: reglas puras del padron de clientes y del comprador por canal.
class ClientesReglasTest {

    private static final TipoPrecioService TIPOS = tipos();
    private final CompradorService compradores = new CompradorService(mock(com.planta.acopio.repository.ClienteRepository.class),
            mock(com.planta.acopio.repository.ClienteTelefonoRepository.class), mock(com.planta.acopio.repository.ProveedorRepository.class),
            mock(com.planta.acopio.repository.ClienteTipoPrecioRepository.class), TIPOS);

    private static TipoPrecio tipo(String codigo, String nombre, ReglaComprador regla) {
        TipoPrecio t = new TipoPrecio();
        t.setCodigo(codigo);
        t.setNombre(nombre);
        t.setReglaComprador(regla);
        return t;
    }

    private static TipoPrecioService tipos() {
        TipoPrecioService s = mock(TipoPrecioService.class);
        when(s.obtener("PUBLICO")).thenReturn(tipo("PUBLICO", "Público", ReglaComprador.CUALQUIERA));
        when(s.obtener("MAYORISTA")).thenReturn(tipo("MAYORISTA", "Mayorista", ReglaComprador.CLIENTE_HABILITADO));
        when(s.obtener("PROVEEDOR")).thenReturn(tipo("PROVEEDOR", "Proveedor", ReglaComprador.PROVEEDOR_PADRON));
        when(s.obtener("DISTRIBUIDOR")).thenReturn(tipo("DISTRIBUIDOR", "Distribuidor", ReglaComprador.CLIENTE_HABILITADO));
        return s;
    }

    private static CompradorService.Comprador cliente(boolean mayorista, boolean activo) {
        return new CompradorService.Comprador(TipoComprador.CLIENTE, UUID.randomUUID(), "Rosa", null, mayorista ? Set.of("MAYORISTA") : Set.of(), activo);
    }

    private static CompradorService.Comprador proveedor() {
        return new CompradorService.Comprador(TipoComprador.PROVEEDOR, UUID.randomUUID(), "Juan", null, Set.of(), true);
    }

    @Test
    void enPublicoElCompradorEsOpcionalYPuedeSerClienteOProveedor() {
        assertThatCode(() -> compradores.validarParaCanal("PUBLICO", null)).doesNotThrowAnyException();
        assertThatCode(() -> compradores.validarParaCanal("PUBLICO", cliente(false, true))).doesNotThrowAnyException();
        assertThatCode(() -> compradores.validarParaCanal("PUBLICO", proveedor())).doesNotThrowAnyException();
    }

    @Test
    void elCanalHabitualEsPublicoOMayoristaYNoSeConfundeConElTipo() {
        assertThat(ClienteService.canalHabitualImportacion(null, false)).isEqualTo("PUBLICO");
        assertThat(ClienteService.canalHabitualImportacion(" ", true)).isEqualTo("PUBLICO");
        assertThat(ClienteService.canalHabitualImportacion("mayorista", true)).isEqualTo("MAYORISTA");
        assertThat(ClienteService.canalHabitualImportacion("PUBLICO", true)).isEqualTo("PUBLICO"); // un mayorista puede comprar a precio publico
        assertThatThrownBy(() -> ClienteService.canalHabitualImportacion("MAYORISTA", false)).isInstanceOf(ValidacionNegocioException.class)
                .hasMessageContaining("habilitados como mayoristas");
        assertThatThrownBy(() -> ClienteService.canalHabitualImportacion("PROVEEDOR", true)).isInstanceOf(ValidacionNegocioException.class);
        assertThatThrownBy(() -> ClienteService.canalHabitualImportacion("VIP", false)).isInstanceOf(ValidacionNegocioException.class);
    }

    @Test
    void mayoristaExigeUnClienteHabilitadoPorAdmin() {
        assertThatThrownBy(() -> compradores.validarParaCanal("MAYORISTA", null)).isInstanceOf(ValidacionNegocioException.class);
        assertThatThrownBy(() -> compradores.validarParaCanal("MAYORISTA", cliente(false, true)))
                .hasMessageContaining("no está habilitado para Mayorista");
        assertThatThrownBy(() -> compradores.validarParaCanal("MAYORISTA", proveedor())).isInstanceOf(ValidacionNegocioException.class);
        assertThatCode(() -> compradores.validarParaCanal("MAYORISTA", cliente(true, true))).doesNotThrowAnyException();
    }

    @Test
    void unTipoNuevoConReglaClienteHabilitadoExigeLaHabilitacionDeEseTipoSinCodigoEspecial() {
        var soloMayorista = cliente(true, true);
        var habilitadoDistribuidor = new CompradorService.Comprador(TipoComprador.CLIENTE, UUID.randomUUID(), "Rosa", null, Set.of("DISTRIBUIDOR"), true);
        assertThatThrownBy(() -> compradores.validarParaCanal("DISTRIBUIDOR", soloMayorista)).hasMessageContaining("no está habilitado para Distribuidor");
        assertThatThrownBy(() -> compradores.validarParaCanal("DISTRIBUIDOR", null)).isInstanceOf(ValidacionNegocioException.class);
        assertThatCode(() -> compradores.validarParaCanal("DISTRIBUIDOR", habilitadoDistribuidor)).doesNotThrowAnyException();
    }

    @Test
    void proveedorExigeUnProveedorDelPadron() {
        assertThatThrownBy(() -> compradores.validarParaCanal("PROVEEDOR", null)).isInstanceOf(ValidacionNegocioException.class);
        assertThatThrownBy(() -> compradores.validarParaCanal("PROVEEDOR", cliente(true, true))).isInstanceOf(ValidacionNegocioException.class);
        assertThatCode(() -> compradores.validarParaCanal("PROVEEDOR", proveedor())).doesNotThrowAnyException();
    }

    @Test
    void unaFichaDesactivadaNoSeUsaComoComprador() {
        assertThatThrownBy(() -> compradores.validarParaCanal("PUBLICO", cliente(true, false))).hasMessageContaining("desactivada");
    }

    @Test
    void documentosDelClienteDniYRucOpcionalesConLasFormasDeLaFaseG() {
        assertThat(ClienteService.documentos(TipoPersona.NATURAL, " Rosa  Quispe ", null, null, null).nombre()).isEqualTo("Rosa Quispe");
        assertThat(ClienteService.documentos(TipoPersona.NATURAL, "Rosa", "04567890", "10045678909", null).ruc()).isEqualTo("10045678909");
        assertThatThrownBy(() -> ClienteService.documentos(TipoPersona.NATURAL, "Rosa", "123", null, null)).hasMessageContaining("8 dígitos");
        assertThatThrownBy(() -> ClienteService.documentos(TipoPersona.NATURAL, "Rosa", "11111111", "10045678909", null)).hasMessageContaining("no corresponde");
        assertThatThrownBy(() -> ClienteService.documentos(TipoPersona.NATURAL, "", null, null, null)).hasMessageContaining("nombre");
        var empresa = ClienteService.documentos(TipoPersona.EMPRESA, null, null, "20601234565", "Bodega San Martín S.A.C.");
        assertThat(empresa.nombre()).isEqualTo("Bodega San Martín S.A.C.");
        assertThatThrownBy(() -> ClienteService.documentos(TipoPersona.EMPRESA, null, "04567890", "20601234565", "X")).hasMessageContaining("no registra DNI");
        assertThatThrownBy(() -> ClienteService.documentos(TipoPersona.EMPRESA, null, null, null, "X")).hasMessageContaining("RUC es obligatorio");
    }

    @Test
    void mediosYFormasSeValidanYLosMediosQuedanOrdenadosSinRepetir() {
        assertThat(ClienteService.medios(Arrays.asList("whatsapp", "CORREO", "WhatsApp", null, " "))).containsExactly("CORREO", "WHATSAPP");
        assertThatThrownBy(() -> ClienteService.medios(List.of())).hasMessageContaining("al menos un medio");
        assertThatThrownBy(() -> ClienteService.medios(List.of("PALOMA"))).hasMessageContaining("no válido");
        assertThat(ClienteService.forma("verbal_presencial")).isEqualTo("VERBAL_PRESENCIAL");
        assertThatThrownBy(() -> ClienteService.forma("POR_SUPUESTO")).isInstanceOf(ValidacionNegocioException.class);
        assertThat(ClienteService.telefono("987 654 321")).isEqualTo("+51987654321");
    }

    @Test
    void enmascaraLoQueUnRolSinAccesoCompletoSoloNecesitaParaConfirmar() {
        assertThat(Enmascarar.telefono("+51987654321")).isEqualTo("••• ••• 321");
        assertThat(Enmascarar.documento("04567890")).isEqualTo("••••7890");
        assertThat(Enmascarar.documento("20601234565")).isEqualTo("•••••••4565");
        assertThat(Enmascarar.correo("rosa.quispe@correo.pe")).isEqualTo("r•••@correo.pe");
        assertThat(Enmascarar.telefono(null)).isNull();
    }

    @Test
    void elAvisoSinDatosDelResponsableQuedaIncompletoYNoInventaNada() {
        PrivacidadService vacio = new PrivacidadService("v1", "", "", "", "", "", "", "", "", "");
        var aviso = vacio.avisos();
        assertThat(aviso.completo()).isFalse();
        assertThat(aviso.faltantes()).hasSize(9).anyMatch(f -> f.contains("Razón social")).anyMatch(f -> f.contains("Banco de datos"));
        assertThat(String.join(" ", aviso.avisoReserva())).doesNotContain("Responsable:").contains("solo para gestionar esta reserva")
                .contains("No usamos estos datos para enviarte publicidad");
        assertThat(String.join(" ", aviso.avisoRegistro())).contains("voluntario").contains("no significa aceptar publicidad");
        assertThat(aviso.textoComunicaciones()).contains("opcional").contains("10 días");

        PrivacidadService lleno = new PrivacidadService("v2", "EcoLácteos S.A.C.", "20601234565", "Jr. Lima 123, Huata", "datos@ecolacteos.pe",
                "Clientes", "el proveedor de alojamiento X", "Los datos se alojan en el Perú.", "hasta un año después del retiro",
                "mientras seas cliente y hasta un año después de tu última compra");
        assertThat(lleno.completo()).isTrue();
        assertThat(String.join(" ", lleno.avisos().avisoReserva())).contains("EcoLácteos S.A.C. (RUC 20601234565)").contains("datos@ecolacteos.pe")
                .contains("hasta un año después del retiro");
    }
}
