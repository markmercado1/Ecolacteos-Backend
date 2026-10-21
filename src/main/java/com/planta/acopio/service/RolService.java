package com.planta.acopio.service;

import com.planta.acopio.dto.RolResponse;
import com.planta.acopio.repository.RolRepository;
import org.springframework.stereotype.Service;

import java.util.List;

// Auditoria CRUD (prioridad MEDIA): solo lectura. Rol es un catalogo fijo
// sembrado por schema.sql (ACOPIADOR, CALIDAD, RECEPCION, PRODUCCION, VENTAS,
// ADMIN) -- correctamente sin crear/editar/eliminar desde la API. Este
// service existe unicamente para que el frontend pueda poblar el selector de
// roles del formulario de alta/edicion de Usuario sin hardcodear la lista.
@Service
public class RolService {

    private final RolRepository repository;

    public RolService(RolRepository repository) {
        this.repository = repository;
    }

    public List<RolResponse> listar() {
        return repository.findAll().stream()
                .map(r -> new RolResponse(r.getId(), r.getNombre()))
                .toList();
    }
}
