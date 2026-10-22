package com.planta.acopio.service;

import com.planta.acopio.dto.ZonaRequest;
import com.planta.acopio.dto.ZonaResponse;
import com.planta.acopio.entity.Zona;
import com.planta.acopio.exception.RecursoNoEncontradoException;
import com.planta.acopio.repository.ZonaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class ZonaService {

    private final ZonaRepository zonaRepository;

    public ZonaService(ZonaRepository zonaRepository) {
        this.zonaRepository = zonaRepository;
    }

    @Transactional
    public ZonaResponse crear(ZonaRequest dto) {
        Zona zona = new Zona();
        zona.setNombre(dto.nombre());
        return toResponse(zonaRepository.save(zona));
    }

    public List<ZonaResponse> listarActivas() {
        return zonaRepository.findByActivaTrue(OrdenCatalogo.POR_NOMBRE).stream().map(this::toResponse).toList();
    }

    public ZonaResponse obtener(UUID id) {
        return toResponse(zonaRepository.findById(id)
                .orElseThrow(() -> new RecursoNoEncontradoException("Zona no encontrada: " + id)));
    }

    // Auditoria CRUD (prioridad MEDIA): antes no habia forma de corregir el
    // nombre de una zona ya creada.
    @Transactional
    public ZonaResponse actualizar(UUID id, ZonaRequest dto) {
        Zona zona = zonaRepository.findById(id)
                .orElseThrow(() -> new RecursoNoEncontradoException("Zona no encontrada: " + id));
        zona.setNombre(dto.nombre());
        return toResponse(zona);
    }

    @Transactional
    public void desactivar(UUID id) {
        Zona zona = zonaRepository.findById(id)
                .orElseThrow(() -> new RecursoNoEncontradoException("Zona no encontrada: " + id));
        zona.setActiva(false);
    }

    // Auditoria de bajo riesgo (Fase 3): la baja logica ya existia sin forma
    // de revertirla desde la API -- espejo exacto de desactivar().
    @Transactional
    public ZonaResponse reactivar(UUID id) {
        Zona zona = zonaRepository.findById(id)
                .orElseThrow(() -> new RecursoNoEncontradoException("Zona no encontrada: " + id));
        zona.setActiva(true);
        return toResponse(zona);
    }

    private ZonaResponse toResponse(Zona z) {
        return new ZonaResponse(z.getId(), z.getNombre(), z.getActiva());
    }
}
