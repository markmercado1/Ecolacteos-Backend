package com.planta.acopio.repository;

import com.planta.acopio.entity.RegularizacionTrazabilidad;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface RegularizacionTrazabilidadRepository extends JpaRepository<RegularizacionTrazabilidad, UUID> {

    // Total ya reclasificado desde "sin trazabilidad" hacia lotes regularizados, para una presentacion.
    @Query("SELECT COALESCE(SUM(r.cantidad), 0) FROM RegularizacionTrazabilidad r WHERE r.presentacion.id = :presentacionId")
    long sumarPorPresentacion(@Param("presentacionId") UUID presentacionId);
}
