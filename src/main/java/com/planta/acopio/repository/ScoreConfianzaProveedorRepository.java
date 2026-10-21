package com.planta.acopio.repository;

import com.planta.acopio.entity.ScoreConfianzaProveedor;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ScoreConfianzaProveedorRepository extends JpaRepository<ScoreConfianzaProveedor, UUID> {
    Optional<ScoreConfianzaProveedor> findByProveedorIdAndPeriodo(UUID proveedorId, LocalDate periodo);
    Optional<ScoreConfianzaProveedor> findTopByProveedorIdOrderByPeriodoDesc(UUID proveedorId);

    // Usado por PanelEconomicoService (simulador "que pasa si", RF-INN-05):
    // score promedio de la zona, agrupado por periodo, para el eje X de la
    // regresion lineal simple. Object[] = {periodo (Date), scorePromedio (Numeric)}.
    @Query(value = "SELECT s.periodo, AVG(s.score) FROM score_confianza_proveedor s " +
           "JOIN proveedor p ON s.proveedor_id = p.id " +
           "WHERE p.zona_actual_id = :zonaId GROUP BY s.periodo ORDER BY s.periodo", nativeQuery = true)
    List<Object[]> promedioScorePorPeriodoZona(@Param("zonaId") UUID zonaId);

    @Query(value = "SELECT AVG(s.score) FROM score_confianza_proveedor s " +
           "JOIN proveedor p ON s.proveedor_id = p.id " +
           "WHERE p.zona_actual_id = :zonaId AND s.periodo = (SELECT MAX(s2.periodo) FROM score_confianza_proveedor s2 " +
           "JOIN proveedor p2 ON s2.proveedor_id = p2.id WHERE p2.zona_actual_id = :zonaId)", nativeQuery = true)
    java.math.BigDecimal scorePromedioActualZona(@Param("zonaId") UUID zonaId);
}
