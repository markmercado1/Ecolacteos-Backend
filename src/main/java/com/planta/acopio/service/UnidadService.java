package com.planta.acopio.service;

import com.planta.acopio.dto.UnidadRequest;
import com.planta.acopio.dto.UnidadResponse;
import com.planta.acopio.entity.Unidad;
import com.planta.acopio.entity.Usuario;
import com.planta.acopio.entity.Zona;
import com.planta.acopio.exception.ConflictoNegocioException;
import com.planta.acopio.exception.RecursoNoEncontradoException;
import com.planta.acopio.repository.UnidadRepository;
import com.planta.acopio.repository.UsuarioRepository;
import com.planta.acopio.repository.ZonaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class UnidadService {

    private final UnidadRepository unidadRepository;
    private final ZonaRepository zonaRepository;
    private final UsuarioRepository usuarioRepository;

    public UnidadService(UnidadRepository unidadRepository, ZonaRepository zonaRepository, UsuarioRepository usuarioRepository) {
        this.unidadRepository = unidadRepository;
        this.zonaRepository = zonaRepository;
        this.usuarioRepository = usuarioRepository;
    }

    // mejora #2 (CORE_DESIGN.md §6): responsableId es obligatorio -- el DTO ya
    // lo exige con @NotNull, aqui se valida ademas que exista.
    @Transactional
    public UnidadResponse crear(UnidadRequest dto) {
        if (unidadRepository.findByPlaca(dto.placa()).isPresent()) {
            throw new ConflictoNegocioException("Ya existe una unidad con placa " + dto.placa());
        }
        Usuario responsable = usuarioRepository.findById(dto.responsableId())
                .orElseThrow(() -> new RecursoNoEncontradoException("Usuario responsable no encontrado: " + dto.responsableId()));

        Unidad unidad = new Unidad();
        unidad.setPlaca(dto.placa());
        unidad.setCapacidadTon(dto.capacidadTon());
        unidad.setResponsable(responsable);

        if (dto.zonaId() != null) {
            Zona zona = zonaRepository.findById(dto.zonaId())
                    .orElseThrow(() -> new RecursoNoEncontradoException("Zona no encontrada: " + dto.zonaId()));
            unidad.setZona(zona);
        }

        return toResponse(unidadRepository.save(unidad));
    }

    public List<UnidadResponse> listar() {
        return unidadRepository.findAll().stream().map(this::toResponse).toList();
    }

    // Auditoria CRUD (prioridad MEDIA): faltaba poder reasignar zona/responsable
    // o corregir la placa de una unidad ya creada. Sin desactivar(): a
    // diferencia de Zona/TipoQueso/Proveedor, "unidad" no tiene columna activo
    // en schema.sql -- agregarla es un cambio de esquema fuera de este alcance;
    // si una unidad se retira de operacion en v1, simplemente deja de usarse.
    @Transactional
    public UnidadResponse actualizar(UUID id, UnidadRequest dto) {
        Unidad unidad = unidadRepository.findById(id)
                .orElseThrow(() -> new RecursoNoEncontradoException("Unidad no encontrada: " + id));

        unidadRepository.findByPlaca(dto.placa())
                .filter(otra -> !otra.getId().equals(id))
                .ifPresent(otra -> { throw new ConflictoNegocioException("Ya existe una unidad con placa " + dto.placa()); });

        Usuario responsable = usuarioRepository.findById(dto.responsableId())
                .orElseThrow(() -> new RecursoNoEncontradoException("Usuario responsable no encontrado: " + dto.responsableId()));

        unidad.setPlaca(dto.placa());
        unidad.setCapacidadTon(dto.capacidadTon());
        unidad.setResponsable(responsable);

        if (dto.zonaId() != null) {
            Zona zona = zonaRepository.findById(dto.zonaId())
                    .orElseThrow(() -> new RecursoNoEncontradoException("Zona no encontrada: " + dto.zonaId()));
            unidad.setZona(zona);
        } else {
            unidad.setZona(null);
        }

        return toResponse(unidad);
    }

    private UnidadResponse toResponse(Unidad u) {
        return new UnidadResponse(
                u.getId(), u.getPlaca(), u.getCapacidadTon(),
                u.getZona() != null ? u.getZona().getId() : null,
                u.getResponsable().getId(), u.getResponsable().getNombre());
    }
}
