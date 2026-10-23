package com.planta.acopio.service;

import com.planta.acopio.entity.HistorialZona;
import com.planta.acopio.entity.Zona;
import com.planta.acopio.repository.HistorialZonaRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

// Fase A: la zona de una entrega es la que tenia el proveedor EN EL MOMENTO de la entrega, no la que tiene hoy
// (Proveedor.zonaActual). Se resuelve contra los periodos de historial_zona, y solo si hay exactamente uno valido:
// sin periodo (entrega anterior a la historia) o con mas de uno (no deberia ocurrir) la zona queda sin determinar,
// nunca se adivina con la zona actual.
@Service
public class ZonaHistoricaService {

    private final HistorialZonaRepository historialZonaRepository;

    public ZonaHistoricaService(HistorialZonaRepository historialZonaRepository) {
        this.historialZonaRepository = historialZonaRepository;
    }

    public Optional<Zona> zonaEn(UUID proveedorId, LocalDateTime momento) {
        List<HistorialZona> periodos = historialZonaRepository.periodosValidosEn(proveedorId, momento);
        return periodos.size() == 1 ? Optional.of(periodos.get(0).getZona()) : Optional.empty();
    }
}
