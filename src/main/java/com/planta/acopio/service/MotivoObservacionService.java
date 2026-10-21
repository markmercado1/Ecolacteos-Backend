package com.planta.acopio.service;

import com.planta.acopio.dto.MotivoObservacionRequest;
import com.planta.acopio.dto.MotivoObservacionResponse;
import com.planta.acopio.entity.MotivoObservacion;
import com.planta.acopio.exception.RecursoNoEncontradoException;
import com.planta.acopio.repository.MotivoObservacionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class MotivoObservacionService {

    private final MotivoObservacionRepository repository;

    public MotivoObservacionService(MotivoObservacionRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public MotivoObservacionResponse crear(MotivoObservacionRequest dto) {
        MotivoObservacion m = new MotivoObservacion();
        m.setDescripcion(dto.descripcion());
        m = repository.save(m);
        return new MotivoObservacionResponse(m.getId(), m.getDescripcion());
    }

    public List<MotivoObservacionResponse> listarActivos() {
        return repository.findByActivoTrue().stream()
                .map(m -> new MotivoObservacionResponse(m.getId(), m.getDescripcion()))
                .toList();
    }

    // Auditoria CRUD (prioridad MEDIA): faltaba corregir la descripcion de un
    // motivo ya creado.
    @Transactional
    public MotivoObservacionResponse actualizar(UUID id, MotivoObservacionRequest dto) {
        MotivoObservacion m = repository.findById(id)
                .orElseThrow(() -> new RecursoNoEncontradoException("Motivo de observacion no encontrado: " + id));
        m.setDescripcion(dto.descripcion());
        return new MotivoObservacionResponse(m.getId(), m.getDescripcion());
    }

    // Auditoria CRUD (prioridad MEDIA -- inconsistencia con Zona/TipoQueso):
    // el campo activo ya existia en la entidad, pero ningun metodo lo ponia
    // en false -- listarActivos() nunca podia excluir nada en la practica.
    @Transactional
    public void desactivar(UUID id) {
        MotivoObservacion m = repository.findById(id)
                .orElseThrow(() -> new RecursoNoEncontradoException("Motivo de observacion no encontrado: " + id));
        m.setActivo(false);
    }

    // Auditoria de bajo riesgo (Fase 3): espejo exacto de desactivar().
    @Transactional
    public MotivoObservacionResponse reactivar(UUID id) {
        MotivoObservacion m = repository.findById(id)
                .orElseThrow(() -> new RecursoNoEncontradoException("Motivo de observacion no encontrado: " + id));
        m.setActivo(true);
        return new MotivoObservacionResponse(m.getId(), m.getDescripcion());
    }
}
