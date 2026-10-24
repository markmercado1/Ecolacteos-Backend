package com.planta.acopio.repository;

import com.planta.acopio.entity.TasaIgv;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TasaIgvRepository extends JpaRepository<TasaIgv, UUID> {

    /** La tasa vigente a una fecha: la de vigente_desde mas reciente que no sea posterior. */
    Optional<TasaIgv> findFirstByVigenteDesdeLessThanEqualOrderByVigenteDesdeDesc(LocalDate fecha);

    /** La primera tasa programada despues de una fecha. */
    Optional<TasaIgv> findFirstByVigenteDesdeAfterOrderByVigenteDesdeAsc(LocalDate fecha);

    boolean existsByVigenteDesde(LocalDate vigenteDesde);

    List<TasaIgv> findAllByOrderByVigenteDesdeDesc();
}
