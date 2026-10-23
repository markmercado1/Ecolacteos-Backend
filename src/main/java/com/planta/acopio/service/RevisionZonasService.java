package com.planta.acopio.service;

import com.planta.acopio.dto.RevisionZonasResponse;
import com.planta.acopio.entity.HistorialZona;
import com.planta.acopio.entity.RegistroAcopio;
import com.planta.acopio.repository.HistorialZonaRepository;
import com.planta.acopio.repository.RegistroAcopioRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// Fase A: informe de revision manual de zonas (decision 7 de la auditoria). Solo lectura: lista lo que el sistema no
// pudo decidir -- periodos de zona invertidos y entregas sin zona -- para que una persona los resuelva.
@Service
public class RevisionZonasService {

    static final int LIMITE_ENTREGAS = 500;

    private final HistorialZonaRepository historialZonaRepository;
    private final RegistroAcopioRepository registroAcopioRepository;

    public RevisionZonasService(HistorialZonaRepository historialZonaRepository,
                                RegistroAcopioRepository registroAcopioRepository) {
        this.historialZonaRepository = historialZonaRepository;
        this.registroAcopioRepository = registroAcopioRepository;
    }

    @Transactional(readOnly = true)
    public RevisionZonasResponse informe() {
        List<RevisionZonasResponse.PeriodoParaRevisar> periodos = historialZonaRepository
                .findByRequiereRevisionTrueOrderByProveedorNombreAscInicioEnAsc().stream()
                .map(h -> new RevisionZonasResponse.PeriodoParaRevisar(
                        h.getId(), h.getProveedor().getId(), h.getProveedor().getNombre(), h.getZona().getNombre(),
                        h.getDesde(), h.getHasta(), h.getNotaRevision()))
                .toList();

        Page<RegistroAcopio> sinZona = registroAcopioRepository.findSinZona(PageRequest.of(0, LIMITE_ENTREGAS));
        Map<UUID, List<HistorialZona>> periodosPorProveedor = new HashMap<>();
        List<RevisionZonasResponse.EntregaSinZona> entregas = sinZona.getContent().stream()
                .map(r -> {
                    UUID proveedorId = r.getProveedor().getId();
                    List<HistorialZona> historial = periodosPorProveedor.computeIfAbsent(proveedorId,
                            id -> historialZonaRepository.findByProveedorIdOrderByDesdeDesc(id));
                    return new RevisionZonasResponse.EntregaSinZona(
                            r.getId(), proveedorId, r.getProveedor().getNombre(), r.getFechaHora(), r.getLitros(),
                            motivo(r, historial));
                })
                .toList();

        return new RevisionZonasResponse(periodos, sinZona.getTotalElements(), LIMITE_ENTREGAS, entregas);
    }

    static String motivo(RegistroAcopio r, List<HistorialZona> historial) {
        LocalDate dia = r.getFechaHora().toLocalDate();
        boolean diaDeCambio = historial.stream().anyMatch(cambio -> dia.equals(cambio.getDesde())
                && historial.stream().anyMatch(antes -> antes.getDesde().isBefore(cambio.getDesde())));
        if (diaDeCambio) {
            return "DIA_DE_CAMBIO";
        }
        boolean cubierta = historial.stream().anyMatch(h -> !Boolean.TRUE.equals(h.getRequiereRevision())
                && !h.getInicioEn().isAfter(r.getFechaHora())
                && (h.getFinEn() == null || r.getFechaHora().isBefore(h.getFinEn())));
        return cubierta ? "OTRO" : "SIN_PERIODO";
    }
}
