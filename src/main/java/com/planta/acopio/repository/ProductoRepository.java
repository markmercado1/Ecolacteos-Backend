package com.planta.acopio.repository;

import com.planta.acopio.entity.Producto;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProductoRepository extends JpaRepository<Producto, UUID> {
    List<Producto> findByActivoTrue();
    List<Producto> findByActivoTrue(org.springframework.data.domain.Sort sort);

    // Duplicados: incluye productos desactivados (siguen ocupando el nombre por el UNIQUE de la BD).
    boolean existsByNombreIgnoreCase(String nombre);
    boolean existsByNombreIgnoreCaseAndIdNot(String nombre, UUID id);

    // Lectura ESCALAR del indicador (no la de una entidad posiblemente desactualizada en el contexto de
    // persistencia): se usa despues de tomar el bloqueo de presentacion, para decidir con el valor confirmado.
    @Query("SELECT p.controlaVencimiento FROM Producto p WHERE p.id = :id")
    Optional<Boolean> controlaVencimiento(@Param("id") UUID id);

    // Fase 2: productos que controlan vencimiento (solo ids), para la web.
    @Query("SELECT p.id FROM Producto p WHERE p.controlaVencimiento = true")
    java.util.List<java.util.UUID> idsConControlVencimiento();
}
