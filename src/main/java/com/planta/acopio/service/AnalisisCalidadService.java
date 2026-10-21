package com.planta.acopio.service;

import com.planta.acopio.dto.AnalisisBandejaResponse;
import com.planta.acopio.dto.AnalisisCalidadRequest;
import com.planta.acopio.dto.AnalisisCalidadResponse;
import com.planta.acopio.entity.AnalisisCalidad;
import com.planta.acopio.entity.RegistroAcopio;
import com.planta.acopio.exception.RecursoNoEncontradoException;
import com.planta.acopio.repository.AnalisisCalidadRepository;
import com.planta.acopio.repository.RegistroAcopioRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

// mejora #1 (CORE_DESIGN.md §6, decision aprobada §18 punto 2): capturable
// offline igual que RegistroAcopio -- idempotencia por uuidCliente.
@Service
public class AnalisisCalidadService {

    private final AnalisisCalidadRepository repository;
    private final RegistroAcopioRepository registroAcopioRepository;

    public AnalisisCalidadService(AnalisisCalidadRepository repository, RegistroAcopioRepository registroAcopioRepository) {
        this.repository = repository;
        this.registroAcopioRepository = registroAcopioRepository;
    }

    @Transactional
    public AnalisisCalidadResponse registrarOIgnorarSiDuplicado(AnalisisCalidadRequest dto) {
        var existente = repository.findByUuidCliente(dto.uuidCliente());
        if (existente.isPresent()) {
            return toResponse(existente.get());
        }

        RegistroAcopio registro = registroAcopioRepository.findById(dto.registroAcopioId())
                .orElseThrow(() -> new RecursoNoEncontradoException("Registro de acopio no encontrado: " + dto.registroAcopioId()));

        AnalisisCalidad analisis = new AnalisisCalidad();
        analisis.setUuidCliente(dto.uuidCliente());
        analisis.setRegistroAcopio(registro);
        analisis.setFolioMuestra(dto.folioMuestra());
        analisis.setAgua(dto.agua());
        analisis.setProteina(dto.proteina());
        analisis.setLactosa(dto.lactosa());
        analisis.setDensidad(dto.densidad());
        analisis.setTemperatura(dto.temperatura());
        analisis.setPh(dto.ph());
        analisis.setAguaAnadida(Boolean.TRUE.equals(dto.aguaAnadida()));
        // Regla de resultado simple y explicable (heuristica, no ML): agua
        // anadida detectada -> RECHAZADO; si no, APROBADO por defecto en v1.
        // Un flujo de revision manual mas fino queda fuera de alcance de v1
        // (no descrito con detalle suficiente en el documento fuente).
        analisis.setResultado(Boolean.TRUE.equals(dto.aguaAnadida())
                ? com.planta.acopio.entity.ResultadoCalidad.RECHAZADO
                : com.planta.acopio.entity.ResultadoCalidad.APROBADO);

        return toResponse(repository.save(analisis));
    }

    @Transactional(readOnly = true)
    public List<AnalisisBandejaResponse> listarBandeja(LocalDate fecha) {
        var inicio = fecha.atStartOfDay();
        var analisis = repository.listarBandejaPorFechaDeEntrega(inicio, fecha.plusDays(1).atStartOfDay());
        var ultimoPorEntrega = new LinkedHashMap<UUID, AnalisisCalidad>();
        analisis.forEach(a -> ultimoPorEntrega.putIfAbsent(a.getRegistroAcopio().getId(), a));
        return ultimoPorEntrega.values().stream().map(a -> {
            var entrega = a.getRegistroAcopio();
            return new AnalisisBandejaResponse(
                    entrega.getId(), entrega.getProveedor().getId(), entrega.getProveedor().getNombre(),
                    entrega.getFechaHora(), entrega.getLitros(), a.getResultado().name());
        }).toList();
    }

    public List<AnalisisCalidadResponse> buscarPorFolio(String folio) {
        // RF-CAL-02: contraste de doble muestra.
        return repository.findByFolioMuestra(folio).stream().map(this::toResponse).toList();
    }

    // Auditoria CRUD (prioridad MEDIA): faltaba ver los analisis de un
    // registro de acopio puntual (vista de detalle del registro).
    public List<AnalisisCalidadResponse> buscarPorRegistro(UUID registroAcopioId) {
        return repository.findByRegistroAcopioIdOrderByCreadoEnDesc(registroAcopioId).stream()
                .map(this::toResponse).toList();
    }

    private AnalisisCalidadResponse toResponse(AnalisisCalidad a) {
        return new AnalisisCalidadResponse(
                a.getId(), a.getRegistroAcopio().getId(), a.getFolioMuestra(), a.getAgua(), a.getProteina(),
                a.getLactosa(), a.getDensidad(), a.getTemperatura(), a.getPh(), a.getAguaAnadida(),
                a.getResultado().name(), a.getCreadoEn());
    }
}
