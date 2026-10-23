package com.planta.acopio.repository;

import com.planta.acopio.entity.HistorialZona;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface HistorialZonaRepository extends JpaRepository<HistorialZona, UUID> {

    List<HistorialZona> findByProveedorIdOrderByDesdeDesc(UUID proveedorId);

    // Hallazgo 3.4 / CORE_DESIGN.md §5: soporte para detectar/cerrar el periodo
    // abierto antes de crear uno nuevo. Desde V27 un indice unico parcial
    // garantiza que hay como maximo uno por proveedor.
    Optional<HistorialZona> findByProveedorIdAndFinEnIsNull(UUID proveedorId);

    // Periodos validos que contienen un instante: [inicio_en, fin_en). Los marcados para revision (periodos
    // invertidos del codigo anterior a V27) nunca deciden una zona. Deberia devolver 0 o 1 fila.
    @Query("SELECT h FROM HistorialZona h WHERE h.proveedor.id = :proveedorId AND h.requiereRevision = false " +
           "AND h.inicioEn <= :momento AND (h.finEn IS NULL OR :momento < h.finEn)")
    List<HistorialZona> periodosValidosEn(@Param("proveedorId") UUID proveedorId, @Param("momento") LocalDateTime momento);

    List<HistorialZona> findByRequiereRevisionTrueOrderByProveedorNombreAscInicioEnAsc();
}
