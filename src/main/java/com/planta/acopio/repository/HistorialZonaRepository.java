package com.planta.acopio.repository;

import com.planta.acopio.entity.HistorialZona;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface HistorialZonaRepository extends JpaRepository<HistorialZona, UUID> {

    List<HistorialZona> findByProveedorIdOrderByDesdeDesc(UUID proveedorId);

    // Hallazgo 3.4 / CORE_DESIGN.md §5: soporte para detectar/cerrar el periodo
    // abierto (hasta IS NULL) antes de crear uno nuevo.
    Optional<HistorialZona> findByProveedorIdAndHastaIsNull(UUID proveedorId);
}
