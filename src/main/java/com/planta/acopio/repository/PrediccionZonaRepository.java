package com.planta.acopio.repository;

import com.planta.acopio.entity.PrediccionZona;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

public interface PrediccionZonaRepository extends JpaRepository<PrediccionZona, UUID> {
    Optional<PrediccionZona> findByZonaIdAndSemanaInicio(UUID zonaId, LocalDate semanaInicio);
    Optional<PrediccionZona> findTopByZonaIdOrderBySemanaInicioDesc(UUID zonaId);
}
