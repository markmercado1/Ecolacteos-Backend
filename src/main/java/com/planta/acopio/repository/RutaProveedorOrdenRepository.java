package com.planta.acopio.repository;

import com.planta.acopio.entity.RutaProveedorOrden;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface RutaProveedorOrdenRepository extends JpaRepository<RutaProveedorOrden, UUID> {
    List<RutaProveedorOrden> findByZonaIdOrderByOrdenAsc(UUID zonaId);

    // Auditoria CRUD (prioridad BAJA, RF-RUT-04): RutaZonaService.definirRuta
    // reemplaza la ruta completa de la zona -- borra el orden anterior antes
    // de guardar el nuevo, para no dejar huecos ni ordenes inconsistentes.
    void deleteByZonaId(UUID zonaId);
}
