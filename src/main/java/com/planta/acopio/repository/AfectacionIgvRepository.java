package com.planta.acopio.repository;

import com.planta.acopio.entity.AfectacionIgvPresentacion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AfectacionIgvRepository extends JpaRepository<AfectacionIgvPresentacion, UUID> {

    /** La afectacion vigente de una presentacion a una fecha (vigente_desde mas reciente que no sea posterior). */
    Optional<AfectacionIgvPresentacion> findFirstByPresentacionIdAndVigenteDesdeLessThanEqualOrderByVigenteDesdeDesc(
            UUID presentacionId, LocalDate fecha);

    boolean existsByPresentacionIdAndVigenteDesde(UUID presentacionId, LocalDate vigenteDesde);

    List<AfectacionIgvPresentacion> findByPresentacionIdOrderByVigenteDesdeDesc(UUID presentacionId);

    /** Toda la serie de varias presentaciones en una sola consulta (para resolver vigentes y programadas en memoria). */
    @Query("SELECT a FROM AfectacionIgvPresentacion a WHERE a.presentacion.id IN :ids")
    List<AfectacionIgvPresentacion> findByPresentacionIdIn(@Param("ids") Collection<UUID> ids);
}
