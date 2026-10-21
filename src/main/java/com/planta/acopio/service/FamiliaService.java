package com.planta.acopio.service;

import com.planta.acopio.dto.FamiliaRequest;
import com.planta.acopio.dto.FamiliaResponse;
import com.planta.acopio.entity.Familia;
import com.planta.acopio.exception.RecursoNoEncontradoException;
import com.planta.acopio.repository.FamiliaRepository;
import org.springframework.stereotype.Service;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

// Fase 1 (Producto + Presentacion): catalogo administrable desde la web, mismo patron CRUD que
// ZonaService/MotivoObservacionService. El usuario autorizado crea familias nuevas (Yogurt, Leche,
// Mantequilla, Dulce de leche...) sin tocar codigo ni SQL.
@Service
public class FamiliaService {

    private final FamiliaRepository repository;

    public FamiliaService(FamiliaRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public FamiliaResponse crear(FamiliaRequest dto) {
        Familia f = new Familia();
        f.setNombre(dto.nombre());
        return toResponse(repository.save(f));
    }

    public List<FamiliaResponse> listarActivas() {
        return repository.findByActivoTrue().stream().map(this::toResponse).toList();
    }

    /** Incluye inactivas: sus productos pueden conservar saldo en inventario. */
    @Transactional(readOnly = true)
    public List<FamiliaResponse> listarParaInventario() {
        return repository.findAll(Sort.by("nombre", "id")).stream().map(this::toResponse).toList();
    }

    @Transactional
    public FamiliaResponse actualizar(UUID id, FamiliaRequest dto) {
        Familia f = repository.findById(id)
                .orElseThrow(() -> new RecursoNoEncontradoException("Familia no encontrada: " + id));
        f.setNombre(dto.nombre());
        return toResponse(f);
    }

    @Transactional
    public void desactivar(UUID id) {
        Familia f = repository.findById(id)
                .orElseThrow(() -> new RecursoNoEncontradoException("Familia no encontrada: " + id));
        f.setActivo(false);
    }

    @Transactional
    public FamiliaResponse reactivar(UUID id) {
        Familia f = repository.findById(id)
                .orElseThrow(() -> new RecursoNoEncontradoException("Familia no encontrada: " + id));
        f.setActivo(true);
        return toResponse(f);
    }

    private FamiliaResponse toResponse(Familia f) {
        return new FamiliaResponse(f.getId(), f.getNombre(), f.getActivo());
    }
}
