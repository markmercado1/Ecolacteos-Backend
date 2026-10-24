package com.planta.acopio.repository;

import com.planta.acopio.entity.AlertaAnomalia;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface AlertaAnomaliaRepository extends JpaRepository<AlertaAnomalia, UUID> {

    List<AlertaAnomalia> findByRegistroAcopioId(UUID registroAcopioId);

    @Query("SELECT COUNT(a) FROM AlertaAnomalia a WHERE a.registroAcopio.proveedor.id = :proveedorId AND a.creadoEn >= :desde")
    long contarPorProveedorDesde(@Param("proveedorId") UUID proveedorId, @Param("desde") LocalDateTime desde);

    @Query("SELECT a FROM AlertaAnomalia a WHERE a.registroAcopio.proveedor.zonaActual.id = :zonaId AND a.creadoEn >= :desde")
    List<AlertaAnomalia> findPorZonaDesde(@Param("zonaId") UUID zonaId, @Param("desde") LocalDateTime desde);

    // Litros de los registros en alerta de la zona en los ultimos N dias, para
    // el KPI "litros con deteccion temprana de riesgo" del panel economico.
    @Query("SELECT COALESCE(SUM(a.registroAcopio.litros), 0) FROM AlertaAnomalia a " +
           "WHERE a.registroAcopio.proveedor.zonaActual.id = :zonaId AND a.creadoEn >= :desde")
    java.math.BigDecimal sumarLitrosEnAlertaPorZonaDesde(@Param("zonaId") UUID zonaId, @Param("desde") LocalDateTime desde);

    // Fase D: alertas de anomalia de un proveedor (todas) y de las entregas de una pagina.
    long countByRegistroAcopioProveedorId(UUID proveedorId);

    List<AlertaAnomalia> findByRegistroAcopioIdIn(java.util.Collection<UUID> registroAcopioIds);
}
