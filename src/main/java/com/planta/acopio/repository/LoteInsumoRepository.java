package com.planta.acopio.repository;

import com.planta.acopio.entity.LoteInsumo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LoteInsumoRepository extends JpaRepository<LoteInsumo, UUID> {

    boolean existsByInsumoIdAndCodigo(UUID insumoId, String codigo);

    List<LoteInsumo> findByDocumentoIngresoIdOrderByCreadoEnAscIdAsc(UUID documentoIngresoId);

    Optional<LoteInsumo> findByInsumoIdAndCodigo(UUID insumoId, String codigo);

    /**
     * Lotes con existencia de un insumo en orden FEFO: primero los que vencen antes; los que no tienen vencimiento van despues,
     * en FIFO por fecha de ingreso. Leer SIEMPRE bajo el bloqueo de la fila del insumo (ver InventarioInsumoService).
     */
    @Query("SELECT l FROM LoteInsumo l WHERE l.insumo.id = :insumoId AND l.cantidadDisponible > 0 "
            + "ORDER BY CASE WHEN l.fechaVencimiento IS NULL THEN 1 ELSE 0 END, l.fechaVencimiento, l.fechaIngreso, l.creadoEn, l.id")
    List<LoteInsumo> disponiblesFefo(@Param("insumoId") UUID insumoId);

    /** El lote mas reciente del insumo (aunque este agotado): destino de un conteo a favor mientras no se cuente por lote. */
    @Query("SELECT l FROM LoteInsumo l WHERE l.insumo.id = :insumoId ORDER BY l.creadoEn DESC, l.id DESC LIMIT 1")
    Optional<LoteInsumo> masReciente(@Param("insumoId") UUID insumoId);

    @Query("SELECT COALESCE(SUM(l.cantidadDisponible), 0) FROM LoteInsumo l WHERE l.insumo.id = :insumoId")
    BigDecimal sumarDisponible(@Param("insumoId") UUID insumoId);

    @Query("SELECT COUNT(l) FROM LoteInsumo l WHERE l.insumo.id = :insumoId")
    long contarPorInsumo(@Param("insumoId") UUID insumoId);
}
