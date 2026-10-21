package com.planta.acopio.repository;

import com.planta.acopio.entity.LoteProduccionDetalle;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LoteProduccionDetalleRepository extends JpaRepository<LoteProduccionDetalle, UUID> {
    List<LoteProduccionDetalle> findByLoteId(UUID loteId);

    // Fase 1: id de la presentacion de una salida, sin cargar la entidad (se necesita para bloquear la
    // presentacion ANTES de leer/modificar el detalle).
    @Query("SELECT d.presentacion.id FROM LoteProduccionDetalle d WHERE d.id = :id")
    Optional<UUID> presentacionIdDe(@Param("id") UUID id);
}
