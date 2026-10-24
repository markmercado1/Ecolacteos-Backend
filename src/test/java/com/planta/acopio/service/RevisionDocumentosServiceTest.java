package com.planta.acopio.service;

import com.planta.acopio.dto.RevisionDocumentosResponse.Observacion;
import com.planta.acopio.entity.Proveedor;
import com.planta.acopio.entity.TipoPersona;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// Fase G: el informe solo LEE los documentos y describe lo que no cumple las reglas; nunca modifica las fichas.
class RevisionDocumentosServiceTest {

    private static Proveedor p(String nombre, TipoPersona tipo, String dni, String ruc) {
        Proveedor p = new Proveedor();
        p.setId(UUID.randomUUID());
        p.setNombre(nombre);
        p.setTipoPersona(tipo);
        p.setDni(dni);
        p.setRuc(ruc);
        p.setActivo(true);
        return p;
    }

    private static List<String> codigos(List<Observacion> obs, String nombre) {
        return obs.stream().filter(o -> o.nombre().equals(nombre)).map(Observacion::codigo).toList();
    }

    @Test
    void fichasCorrectasNoGeneranObservaciones() {
        var obs = RevisionDocumentosService.revisar(List.of(
                p("Rosa", TipoPersona.NATURAL, "04567890", "10045678909"),
                p("Juan", TipoPersona.NATURAL, "11111111", null),
                p("ACME", TipoPersona.EMPRESA, null, "20601234565")));
        assertThat(obs).isEmpty();
    }

    @Test
    void unDniSinOchoDigitosOConFormaDeRucSeInformaSinCorregirlo() {
        Proveedor corto = p("Corto", TipoPersona.NATURAL, "12345", null);
        Proveedor conRuc = p("ConRuc", TipoPersona.NATURAL, "10045678909", null);
        Proveedor letras = p("Letras", TipoPersona.NATURAL, "0456789A", null);
        var obs = RevisionDocumentosService.revisar(List.of(corto, conRuc, letras));

        assertThat(codigos(obs, "Corto")).containsExactly("DNI_FORMATO");
        assertThat(obs.stream().filter(o -> o.nombre().equals("Corto")).findFirst().get().detalle()).contains("(tiene 5)");
        assertThat(codigos(obs, "ConRuc")).containsExactly("DNI_PARECE_RUC");
        assertThat(codigos(obs, "Letras")).containsExactly("DNI_FORMATO");
        assertThat(corto.getDni()).isEqualTo("12345");
        assertThat(conRuc.getDni()).isEqualTo("10045678909");
    }

    @Test
    void dniIgualesSalvoEspaciosOCerosInicialesSonUnDuplicadoProbable() {
        var obs = RevisionDocumentosService.revisar(List.of(
                p("A", TipoPersona.NATURAL, "04567890", null),
                p("B", TipoPersona.NATURAL, "4567890", null),
                p("C", TipoPersona.NATURAL, "0456 7890", null)));
        assertThat(codigos(obs, "A")).containsExactly("DNI_DUPLICADO_PROBABLE");
        assertThat(codigos(obs, "B")).containsExactlyInAnyOrder("DNI_DUPLICADO_PROBABLE", "DNI_FORMATO");
        assertThat(obs.stream().filter(o -> o.nombre().equals("A")).findFirst().get().detalle()).contains("B").contains("C");
    }

    @Test
    void rucInvalidoQueNoCoincideConElDniOQueContieneElDniDeOtraFicha() {
        var obs = RevisionDocumentosService.revisar(List.of(
                p("Malo", TipoPersona.NATURAL, "33333333", "RUCMALO"),
                p("Prefijo", TipoPersona.EMPRESA, null, "10045678909"),
                p("OtroDni", TipoPersona.NATURAL, "22222222", "10045678909"),
                p("Rosa", TipoPersona.NATURAL, "04567890", null)));
        assertThat(codigos(obs, "Malo")).containsExactly("RUC_INVALIDO");
        assertThat(codigos(obs, "Prefijo")).containsExactlyInAnyOrder("RUC_INVALIDO", "POSIBLE_MISMA_PERSONA");
        assertThat(codigos(obs, "OtroDni")).containsExactly("POSIBLE_MISMA_PERSONA", "RUC_NO_COINCIDE_DNI");
        assertThat(codigos(obs, "Rosa")).isEmpty();
    }

    @Test
    void ordenaPorNombreSinAcentos() {
        var obs = RevisionDocumentosService.revisar(List.of(
                p("Ñuñez", TipoPersona.NATURAL, "1", null),
                p("Álvarez", TipoPersona.NATURAL, "2", null)));
        assertThat(obs).extracting(Observacion::nombre).containsExactly("Álvarez", "Ñuñez");
    }
}
