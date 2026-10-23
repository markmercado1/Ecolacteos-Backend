package com.planta.acopio.service;

import com.planta.acopio.entity.HistorialZona;
import com.planta.acopio.entity.RegistroAcopio;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// Fase A: por que una entrega quedo sin zona (informe de revision).
class RevisionZonasServiceTest {

    private static HistorialZona periodo(String desde, String finEn, boolean revision) {
        HistorialZona h = new HistorialZona();
        LocalDate d = LocalDate.parse(desde);
        h.setDesde(d);
        h.setInicioEn(d.atStartOfDay());
        h.setFinEn(finEn == null ? null : LocalDate.parse(finEn).atStartOfDay());
        h.setRequiereRevision(revision);
        return h;
    }

    private static RegistroAcopio entrega(String fechaHora) {
        RegistroAcopio r = new RegistroAcopio();
        r.setFechaHora(LocalDateTime.parse(fechaHora));
        return r;
    }

    private final List<HistorialZona> historial = List.of(
            periodo("2026-01-01", "2026-09-10", false),
            periodo("2026-09-10", null, false));

    @Test
    void unaEntregaEnUnDiaDeCambioDeZonaSeMarcaDiaDeCambio() {
        assertThat(RevisionZonasService.motivo(entrega("2026-09-10T05:00:00"), historial)).isEqualTo("DIA_DE_CAMBIO");
    }

    @Test
    void elDiaDeAltaDelPrimerPeriodoNoEsUnCambio() {
        assertThat(RevisionZonasService.motivo(entrega("2026-01-01T05:00:00"), historial)).isEqualTo("OTRO");
    }

    @Test
    void unaEntregaAnteriorALaHistoriaEsSinPeriodo() {
        assertThat(RevisionZonasService.motivo(entrega("2025-12-31T05:00:00"), historial)).isEqualTo("SIN_PERIODO");
    }

    @Test
    void unPeriodoMarcadoParaRevisionNoCubreNingunaEntrega() {
        List<HistorialZona> soloInvertido = List.of(periodo("2026-09-30", "2026-09-30", true));
        assertThat(RevisionZonasService.motivo(entrega("2026-10-05T05:00:00"), soloInvertido)).isEqualTo("SIN_PERIODO");
    }
}
