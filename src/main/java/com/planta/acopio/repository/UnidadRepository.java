package com.planta.acopio.repository;

import com.planta.acopio.entity.Unidad;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UnidadRepository extends JpaRepository<Unidad, UUID> {
    Optional<Unidad> findByPlaca(String placa);

    // GET /api/auth/me (bloque 1): Usuario no tiene FK propia a Unidad, pero
    // Unidad.responsable_id si apunta a Usuario -- esta es la relacion
    // INVERSA ya existente, expuesta para que el movil no tenga que
    // hardcodear unidadId cuando el usuario ya es responsable de una.
    List<Unidad> findByResponsableId(UUID responsableId);
}
