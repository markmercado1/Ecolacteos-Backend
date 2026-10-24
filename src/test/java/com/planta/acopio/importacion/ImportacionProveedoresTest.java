package com.planta.acopio.importacion;

import com.planta.acopio.dto.ProveedorRequest;
import com.planta.acopio.entity.Proveedor;
import com.planta.acopio.entity.Zona;
import com.planta.acopio.repository.ProveedorRepository;
import com.planta.acopio.repository.ZonaRepository;
import com.planta.acopio.service.ProveedorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ImportacionProveedoresTest {

    private ProveedorService servicio;
    private ProveedorRepository repositorio;
    private ZonaRepository zonas;
    private ImportacionProveedores tipo;
    private Zona norte;
    private Zona sur;

    @BeforeEach
    void preparar() {
        servicio = mock(ProveedorService.class);
        repositorio = mock(ProveedorRepository.class);
        zonas = mock(ZonaRepository.class);
        norte = zona("Zona Norte");
        sur = zona("Sur Ñuñoa");
        when(zonas.findByActivaTrue()).thenReturn(List.of(norte, sur));
        tipo = new ImportacionProveedores(servicio, repositorio, zonas);
    }

    private static Zona zona(String nombre) {
        Zona z = new Zona();
        z.setId(UUID.randomUUID());
        z.setNombre(nombre);
        return z;
    }

    private static CeldaLeida texto(String t) {
        return new CeldaLeida(t, false, false, false);
    }

    private static CeldaLeida numero(String t) {
        return new CeldaLeida(t, true, false, false);
    }

    private static FilaCruda fila(int numero, CeldaLeida nombre, CeldaLeida dni, CeldaLeida telefono, CeldaLeida zona) {
        Map<String, CeldaLeida> celdas = new LinkedHashMap<>();
        celdas.put("nombre", nombre);
        celdas.put("dni", dni);
        celdas.put("telefono", telefono);
        celdas.put("zona", zona);
        return new FilaCruda(numero, celdas);
    }

    private ResultadoFila validar(FilaCruda fila) {
        return tipo.validar(fila, tipo.preparar(List.of(fila)));
    }

    private ResultadoFila validar(CeldaLeida dni) {
        return validar(fila(2, texto("Rosa Quispe"), dni, CeldaLeida.VACIA, CeldaLeida.VACIA));
    }

    // ----------------------------------------------------------------------------------------------- DNI

    @Test
    void unDniDeOchoDigitosConCerosInicialesSePreservaTalCual() {
        var r = validar(texto("00000001"));
        assertThat(r.estado()).isEqualTo(EstadoFila.VALIDA);
        assertThat(r.valores().get("dni")).isEqualTo("00000001");

        assertThat(validar(texto("04567890")).valores().get("dni")).isEqualTo("04567890");
    }

    @Test
    void unDniQueExcelGuardoComoNumeroRecuperaSusCerosInicialesHastaOchoDigitos() {
        assertThat(validar(numero("4567890")).valores().get("dni")).isEqualTo("04567890");
        assertThat(validar(numero("1")).valores().get("dni")).isEqualTo("00000001");
        assertThat(validar(numero("12345678")).valores().get("dni")).isEqualTo("12345678");
    }

    @Test
    void unDniDeTextoCortoNoSeCompletaAdivinando() {
        // Texto "4567890" (7 digitos) no es numero de Excel: puede ser un typo, no se le agregan ceros.
        var r = validar(texto("4567890"));
        assertThat(r.estado()).isEqualTo(EstadoFila.INVALIDA);
        assertThat(r.mensajes().get(0)).contains("exactamente 8 dígitos").contains("tiene 7");
    }

    @Test
    void rechazaDniConLetrasMasDigitosONumeroDecimal() {
        assertThat(validar(texto("1234567A")).estado()).isEqualTo(EstadoFila.INVALIDA);
        assertThat(validar(texto("123456789")).mensajes().get(0)).contains("tiene 9");
        assertThat(validar(numero("123456789")).estado()).isEqualTo(EstadoFila.INVALIDA);
        assertThat(validar(numero("1234.5")).estado()).isEqualTo(EstadoFila.INVALIDA);
        assertThat(validar(texto("12-345-678")).estado()).isEqualTo(EstadoFila.INVALIDA);
    }

    @Test
    void elDniVacioEsObligatorio() {
        var r = validar(CeldaLeida.VACIA);
        assertThat(r.estado()).isEqualTo(EstadoFila.INVALIDA);
        assertThat(r.mensajes()).anyMatch(m -> m.contains("DNI: es obligatorio"));
    }

    // ------------------------------------------------------------------------------------------ telefono

    @Test
    void elTelefonoSeNormalizaAFormatoInternacionalYEsOpcional() {
        for (String entrada : List.of("987654321", "987 654 321", "+51 987-654-321", "51987654321", "(987) 654321")) {
            var r = validar(fila(2, texto("Rosa"), texto("11111111"), texto(entrada), CeldaLeida.VACIA));
            assertThat(r.estado()).as(entrada).isEqualTo(EstadoFila.VALIDA);
            assertThat(r.valores().get("telefono")).as(entrada).isEqualTo("+51987654321");
        }
        assertThat(validar(fila(2, texto("Rosa"), texto("11111111"), numero("987654321"), CeldaLeida.VACIA)).valores().get("telefono"))
                .isEqualTo("+51987654321");
        assertThat(validar(fila(2, texto("Rosa"), texto("11111111"), CeldaLeida.VACIA, CeldaLeida.VACIA)).valores().get("telefono")).isEmpty();
    }

    @Test
    void rechazaTelefonosQueNoSonCelularesPeruanos() {
        for (String entrada : List.of("12345678", "887654321", "9876543", "98765432100", "abcdefghi", "+34 612345678")) {
            var r = validar(fila(2, texto("Rosa"), texto("11111111"), texto(entrada), CeldaLeida.VACIA));
            assertThat(r.estado()).as(entrada).isEqualTo(EstadoFila.INVALIDA);
            assertThat(r.mensajes().get(0)).contains("Teléfono").contains("celular peruano");
        }
    }

    // ---------------------------------------------------------------------------------------------- zona

    @Test
    void laZonaSeBuscaSinDistinguirMayusculasNiAcentosYSeGuardaSuId() {
        var r = validar(fila(2, texto("Rosa"), texto("11111111"), CeldaLeida.VACIA, texto("  SUR   nunoa ")));
        assertThat(r.estado()).isEqualTo(EstadoFila.VALIDA);
        assertThat(r.valores().get("zona")).isEqualTo("Sur Ñuñoa");
        assertThat(r.valores().get("zonaId")).isEqualTo(sur.getId().toString());
    }

    @Test
    void unaZonaInexistenteOInactivaEsInvalidaYNoSeCreaSola() {
        var r = validar(fila(2, texto("Rosa"), texto("11111111"), CeldaLeida.VACIA, texto("Zona Fantasma")));
        assertThat(r.estado()).isEqualTo(EstadoFila.INVALIDA);
        assertThat(r.mensajes().get(0)).contains("Zona Fantasma").contains("no existe o está desactivada");
    }

    @Test
    void sinZonaEsValidoYQuedaVacia() {
        var r = validar(fila(2, texto("Rosa"), texto("11111111"), CeldaLeida.VACIA, CeldaLeida.VACIA));
        assertThat(r.estado()).isEqualTo(EstadoFila.VALIDA);
        assertThat(r.valores().get("zonaId")).isEmpty();
    }

    // ---------------------------------------------------------------------------------------------- nombre

    @Test
    void elNombreEsObligatorioConLongitudMaximaYSeLimpiaDeEspacios() {
        assertThat(validar(fila(2, CeldaLeida.VACIA, texto("11111111"), CeldaLeida.VACIA, CeldaLeida.VACIA)).mensajes().get(0))
                .contains("Nombre completo: es obligatorio");
        assertThat(validar(fila(2, texto("x".repeat(151)), texto("11111111"), CeldaLeida.VACIA, CeldaLeida.VACIA)).mensajes().get(0))
                .contains("151 caracteres").contains("150");
        assertThat(validar(fila(2, texto("x".repeat(150)), texto("11111111"), CeldaLeida.VACIA, CeldaLeida.VACIA)).estado()).isEqualTo(EstadoFila.VALIDA);
        assertThat(validar(fila(2, texto("  Rosa    Quispe \t Huamán  "), texto("11111111"), CeldaLeida.VACIA, CeldaLeida.VACIA)).valores().get("nombre"))
                .isEqualTo("Rosa Quispe Huamán");
    }

    @Test
    void variosErroresEnUnaFilaSeInformanJuntosConLosValoresOriginales() {
        var r = validar(fila(7, CeldaLeida.VACIA, texto("12"), texto("123"), texto("Nada")));
        assertThat(r.estado()).isEqualTo(EstadoFila.INVALIDA);
        assertThat(r.mensajes()).hasSize(4);
        assertThat(r.valores()).containsEntry("dni", "12").containsEntry("zona", "Nada").containsEntry("telefono", "123");
    }

    // ----------------------------------------------------------------------------------------- formulas

    @Test
    void unaFormulaOUnErrorDeExcelInvalidanLaFilaYNoSeImportaSuResultado() {
        var r = validar(fila(3, new CeldaLeida("", false, true, false), texto("11111111"), CeldaLeida.VACIA, CeldaLeida.VACIA));
        assertThat(r.estado()).isEqualTo(EstadoFila.INVALIDA);
        assertThat(r.mensajes().get(0)).contains("contiene una fórmula");
        assertThat(r.valores().get("nombre")).isEqualTo("(fórmula)");

        var e = validar(fila(3, texto("Rosa"), new CeldaLeida("", false, false, true), CeldaLeida.VACIA, CeldaLeida.VACIA));
        assertThat(e.estado()).isEqualTo(EstadoFila.INVALIDA);
        assertThat(e.mensajes().get(0)).contains("error de Excel");
    }

    // ------------------------------------------------------------------------------------------ duplicados

    @Test
    void unDniQueYaExisteEsDuplicadoAunSiElProveedorEstaInactivo() {
        when(repositorio.dnisExistentes(any())).thenReturn(List.of("11111111"));
        var f = fila(2, texto("Otro Nombre"), texto("11111111"), CeldaLeida.VACIA, CeldaLeida.VACIA);

        var r = tipo.validar(f, tipo.preparar(List.of(f)));

        assertThat(r.estado()).isEqualTo(EstadoFila.DUPLICADA);
        assertThat(r.mensajes().get(0)).contains("11111111").contains("No se modifica");
    }

    @Test
    void elDniNumericoSinCerosTambienSeComparaConLaBaseYaCompletado() {
        when(repositorio.dnisExistentes(any())).thenReturn(List.of("04567890"));
        var f = fila(2, texto("Rosa"), numero("4567890"), CeldaLeida.VACIA, CeldaLeida.VACIA);

        ArgumentCaptor<java.util.Collection<String>> consultados = ArgumentCaptor.forClass(java.util.Collection.class);
        var r = tipo.validar(f, tipo.preparar(List.of(f)));

        verify(repositorio).dnisExistentes(consultados.capture());
        assertThat(consultados.getValue()).containsExactly("04567890");
        assertThat(r.estado()).isEqualTo(EstadoFila.DUPLICADA);
    }

    @Test
    void consultaLaBaseUnaSolaVezPorArchivoYSoloConLosDniValidos() {
        var filas = List.of(
                fila(2, texto("A"), texto("11111111"), CeldaLeida.VACIA, CeldaLeida.VACIA),
                fila(3, texto("B"), texto("malo"), CeldaLeida.VACIA, CeldaLeida.VACIA),
                fila(4, texto("C"), numero("22"), CeldaLeida.VACIA, CeldaLeida.VACIA));
        when(repositorio.dnisExistentes(any())).thenReturn(List.of());

        tipo.preparar(filas);

        ArgumentCaptor<java.util.Collection<String>> consultados = ArgumentCaptor.forClass(java.util.Collection.class);
        verify(repositorio).dnisExistentes(consultados.capture());
        assertThat(consultados.getValue()).containsExactlyInAnyOrder("11111111", "00000022");
    }

    @Test
    void claveUnicaEsElDni() {
        assertThat(tipo.claveUnica(Map.of("dni", "11111111", "nombre", "x"))).isEqualTo("11111111");
    }

    // --------------------------------------------------------------------------------- confirmar y crear

    @Test
    void creaElProveedorPorElServicioDeNegocioNoPorElRepositorio() {
        Map<String, String> valores = new HashMap<>();
        valores.put("nombre", "Rosa Quispe");
        valores.put("dni", "04567890");
        valores.put("telefono", "+51987654321");
        valores.put("zonaId", norte.getId().toString());

        tipo.crear(valores);

        ArgumentCaptor<ProveedorRequest> pedido = ArgumentCaptor.forClass(ProveedorRequest.class);
        verify(servicio).crear(pedido.capture());
        assertThat(pedido.getValue()).isEqualTo(new ProveedorRequest("Rosa Quispe", "04567890", "+51987654321", norte.getId(), "NATURAL", null, null));
        org.mockito.Mockito.verifyNoMoreInteractions(servicio);
    }

    @Test
    void sinTelefonoNiZonaSeEnvianNulos() {
        tipo.crear(Map.of("nombre", "Rosa", "dni", "11111111", "telefono", "", "zonaId", ""));
        ArgumentCaptor<ProveedorRequest> pedido = ArgumentCaptor.forClass(ProveedorRequest.class);
        verify(servicio).crear(pedido.capture());
        assertThat(pedido.getValue().telefono()).isNull();
        assertThat(pedido.getValue().zonaActualId()).isNull();
    }

    @Test
    void alConfirmarOmiteSiElDniSeRegistroEntreTantoOSiLaZonaYaNoEstaActiva() {
        when(repositorio.findByDni("11111111")).thenReturn(Optional.of(new Proveedor()));
        assertThat(tipo.conflictoAlConfirmar(Map.of("dni", "11111111", "zonaId", ""))).hasValueSatisfying(m -> assertThat(m).contains("después de la vista previa"));

        when(repositorio.findByDni("22222222")).thenReturn(Optional.empty());
        Zona inactiva = zona("Vieja");
        inactiva.setActiva(false);
        when(zonas.findById(inactiva.getId())).thenReturn(Optional.of(inactiva));
        assertThat(tipo.conflictoAlConfirmar(Map.of("dni", "22222222", "zona", "Vieja", "zonaId", inactiva.getId().toString())))
                .hasValueSatisfying(m -> assertThat(m).contains("ya no está activa"));

        when(zonas.findById(norte.getId())).thenReturn(Optional.of(norte));
        assertThat(tipo.conflictoAlConfirmar(Map.of("dni", "22222222", "zona", "Zona Norte", "zonaId", norte.getId().toString()))).isEmpty();
    }

    @Test
    void laPlantillaPideTodasLasZonasActivasOrdenadas() {
        assertThat(tipo.listasDesplegables().get("zona")).containsExactly("Sur Ñuñoa", "Zona Norte");
        assertThat(tipo.listasAbiertas()).isEmpty();
        assertThat(Set.of(tipo.codigo())).containsExactly("proveedores");
        assertThat(tipo.version()).isEqualTo(2);
        assertThat(tipo.listasDesplegables().get("tipo")).containsExactly("Persona natural", "Empresa");
    }
    // ------------------------------------------------------------------------------- Fase G: tipo, RUC y razon social

    private static FilaCruda filaG(CeldaLeida tipoPersona, CeldaLeida nombre, CeldaLeida dni, CeldaLeida ruc, CeldaLeida razonSocial) {
        Map<String, CeldaLeida> celdas = new LinkedHashMap<>();
        celdas.put("tipo", tipoPersona);
        celdas.put("nombre", nombre);
        celdas.put("dni", dni);
        celdas.put("ruc", ruc);
        celdas.put("razonSocial", razonSocial);
        celdas.put("telefono", CeldaLeida.VACIA);
        celdas.put("zona", CeldaLeida.VACIA);
        return new FilaCruda(2, celdas);
    }

    @Test
    void sinTipoEsPersonaNaturalYPuedeTenerSuRucDiezConSuDni() {
        var r = validar(filaG(CeldaLeida.VACIA, texto("Rosa Quispe"), texto("04567890"), texto("10045678909"), CeldaLeida.VACIA));
        assertThat(r.estado()).isEqualTo(EstadoFila.VALIDA);
        assertThat(r.valores()).containsEntry("tipo", "Persona natural").containsEntry("tipoPersona", "NATURAL")
                .containsEntry("dni", "04567890").containsEntry("ruc", "10045678909").containsEntry("razonSocial", "");
    }

    @Test
    void unRucQueNoContieneElDniOConVerificadorIncorrectoInvalidaLaFila() {
        var otroDni = validar(filaG(texto("Persona natural"), texto("Rosa"), texto("11111111"), texto("10045678909"), CeldaLeida.VACIA));
        assertThat(otroDni.estado()).isEqualTo(EstadoFila.INVALIDA);
        assertThat(otroDni.mensajes()).anyMatch(m -> m.contains("no corresponde al DNI 11111111"));

        var verificador = validar(filaG(CeldaLeida.VACIA, texto("Rosa"), texto("04567890"), texto("10045678900"), CeldaLeida.VACIA));
        assertThat(verificador.mensajes()).anyMatch(m -> m.contains("dígito verificador"));

        var corto = validar(filaG(CeldaLeida.VACIA, texto("Rosa"), texto("04567890"), texto("1004567890"), CeldaLeida.VACIA));
        assertThat(corto.mensajes()).anyMatch(m -> m.startsWith("RUC:") && m.contains("(tiene 10)"));
    }

    @Test
    void unaEmpresaLlevaRucVeinteYRazonSocialSinDniYSuNombrePorDefectoEsLaRazonSocial() {
        var r = validar(filaG(texto("EMPRESA"), CeldaLeida.VACIA, CeldaLeida.VACIA, numero("20601234565"), texto("Lácteos del Valle S.A.C.")));
        assertThat(r.estado()).isEqualTo(EstadoFila.VALIDA);
        assertThat(r.valores()).containsEntry("tipo", "Empresa").containsEntry("tipoPersona", "EMPRESA").containsEntry("dni", "")
                .containsEntry("ruc", "20601234565").containsEntry("nombre", "Lácteos del Valle S.A.C.");
        assertThat(tipo.claveUnica(r.valores())).isEqualTo("RUC 20601234565");
    }

    @Test
    void unaEmpresaSinRucSinRazonSocialConDniOConRucDiezEsInvalida() {
        assertThat(validar(filaG(texto("Empresa"), CeldaLeida.VACIA, CeldaLeida.VACIA, CeldaLeida.VACIA, texto("ACME"))).mensajes())
                .anyMatch(m -> m.contains("RUC es obligatorio"));
        assertThat(validar(filaG(texto("Empresa"), CeldaLeida.VACIA, CeldaLeida.VACIA, texto("20601234565"), CeldaLeida.VACIA)).mensajes())
                .anyMatch(m -> m.contains("razón social es obligatoria"));
        assertThat(validar(filaG(texto("Empresa"), CeldaLeida.VACIA, texto("04567890"), texto("20601234565"), texto("ACME"))).mensajes())
                .anyMatch(m -> m.contains("no registra DNI"));
        assertThat(validar(filaG(texto("Empresa"), CeldaLeida.VACIA, CeldaLeida.VACIA, texto("10045678909"), texto("ACME"))).mensajes())
                .anyMatch(m -> m.contains("empieza con 20"));
    }

    @Test
    void unaPersonaNaturalNoLlevaRazonSocialYUnTipoDesconocidoSeRechaza() {
        assertThat(validar(filaG(CeldaLeida.VACIA, texto("Rosa"), texto("04567890"), CeldaLeida.VACIA, texto("ACME"))).mensajes())
                .anyMatch(m -> m.contains("solo se registra para una empresa"));
        assertThat(validar(filaG(texto("Cooperativa"), texto("Rosa"), texto("04567890"), CeldaLeida.VACIA, CeldaLeida.VACIA)).mensajes())
                .anyMatch(m -> m.startsWith("Tipo de persona:"));
    }

    @Test
    void unRucQueYaExisteEsDuplicadoYSeConsultaUnaSolaVez() {
        when(repositorio.rucsExistentes(any())).thenReturn(List.of("20601234565"));
        var f = filaG(texto("Empresa"), CeldaLeida.VACIA, CeldaLeida.VACIA, texto("20601234565"), texto("ACME"));
        var r = tipo.validar(f, tipo.preparar(List.of(f)));
        assertThat(r.estado()).isEqualTo(EstadoFila.DUPLICADA);
        assertThat(r.mensajes().get(0)).contains("RUC 20601234565");
        verify(repositorio, org.mockito.Mockito.times(1)).rucsExistentes(any());
    }

    @Test
    void alConfirmarOmiteSiElRucSeRegistroEntreTantoYCreaLaEmpresaPorElServicio() {
        when(repositorio.findByRuc("20601234565")).thenReturn(Optional.of(new Proveedor()));
        assertThat(tipo.conflictoAlConfirmar(Map.of("dni", "", "ruc", "20601234565", "zonaId", "")))
                .hasValueSatisfying(m -> assertThat(m).contains("RUC 20601234565"));

        tipo.crear(Map.of("tipoPersona", "EMPRESA", "nombre", "ACME", "dni", "", "ruc", "20601234565", "razonSocial", "ACME S.A.C.",
                "telefono", "", "zonaId", ""));
        ArgumentCaptor<ProveedorRequest> pedido = ArgumentCaptor.forClass(ProveedorRequest.class);
        verify(servicio).crear(pedido.capture());
        assertThat(pedido.getValue()).isEqualTo(new ProveedorRequest("ACME", null, null, null, "EMPRESA", "20601234565", "ACME S.A.C."));
    }
}
