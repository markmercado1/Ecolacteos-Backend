package com.planta.acopio.repository;

import com.planta.acopio.entity.MovimientoInsumo;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

// Kardex de insumos (V19). Mismo contrato de columnas que los kardex de leche y producto terminado (ver
// KardexService.fila()): id, creado_en, tipo, cantidad, saldo acumulado, documento_tipo, documento_id,
// documento_fecha, referencia, usuario, motivo. El saldo se acumula con una funcion de ventana sobre TODO el
// libro del insumo (orden de escritura, columna `orden` de V40) y recien despues se filtra por fechas y se pagina: el saldo de cada
// fila es siempre el real, aunque la pagina o el rango no empiecen en el primer movimiento.
public interface MovimientoInsumoRepository extends JpaRepository<MovimientoInsumo, UUID> {
    // Una salida puede repartirse entre varios lotes: un movimiento por lote (el primero es el que se informa).
    List<MovimientoInsumo> findBySalidaIdOrderByOrdenAsc(UUID salidaId);


    @Query("SELECT COALESCE(SUM(m.cantidad), 0) FROM MovimientoInsumo m WHERE m.insumo.id = :insumoId")
    BigDecimal sumarCantidad(@Param("insumoId") UUID insumoId);

    // Orden del libro: `orden` (V40), unico y creciente en el orden de escritura. Antes era (creado_en, id) y el id es un
    // UUID aleatorio: movimientos con el mismo instante salian en un orden arbitrario y el saldo acumulado, desordenado.
    @Query(value = "SELECT t.id, t.creado_en, t.tipo_movimiento, t.cantidad, t.saldo, t.documento_tipo, "
            + "t.documento_id, t.documento_fecha, t.referencia, t.usuario_nombre, t.motivo, "
            + "t.lote_id, t.lote_codigo, t.proveedor_nombre, t.fecha_vencimiento, t.doc_tipo, t.doc_numero FROM ("
            + "  SELECT m.id, m.orden, m.creado_en, m.tipo_movimiento, m.cantidad, "
            + "    SUM(m.cantidad) OVER (ORDER BY m.orden) AS saldo, "
            + "    CASE WHEN s.id IS NOT NULL THEN 'SALIDA' WHEN m.tipo_movimiento = 'SALIDA_PRODUCCION' THEN 'LOTE' ELSE m.tipo_movimiento END AS documento_tipo, "
            + "    COALESCE(s.id, l.id) AS documento_id, COALESCE(CAST(s.creado_en AS date), l.fecha) AS documento_fecha, "
            + "    CASE WHEN l.id IS NOT NULL THEN CONCAT('Lote de producción · ', p.nombre) WHEN s.id IS NOT NULL THEN CONCAT('Retiro de insumo · ', s.tipo) ELSE m.referencia END AS referencia, "
            + "    u.nombre AS usuario_nombre, m.motivo, "
            + "    li.id AS lote_id, li.codigo AS lote_codigo, pc.nombre AS proveedor_nombre, li.fecha_vencimiento, di.tipo AS doc_tipo, di.numero AS doc_numero "
            + "  FROM movimiento_insumo m "
            + "  JOIN lote_insumo li ON li.id = m.lote_insumo_id "
            + "  LEFT JOIN proveedor_compra pc ON pc.id = li.proveedor_id "
            + "  LEFT JOIN documento_ingreso di ON di.id = li.documento_ingreso_id "
            + "  LEFT JOIN salida_inventario s ON s.id = m.salida_id "
            + "  LEFT JOIN lote_produccion l ON l.id = m.lote_produccion_id "
            + "  LEFT JOIN producto p ON p.id = l.producto_id "
            + "  LEFT JOIN usuario u ON u.id = m.usuario_id "
            + "  WHERE m.insumo_id = CAST(:insumoId AS uuid) AND (CAST(:loteId AS uuid) IS NULL OR m.lote_insumo_id = CAST(:loteId AS uuid))"
            + ") t "
            + "WHERE (CAST(:desde AS timestamp) IS NULL OR t.creado_en >= CAST(:desde AS timestamp)) "
            + "AND (CAST(:hasta AS timestamp) IS NULL OR t.creado_en < CAST(:hasta AS timestamp)) "
            + "ORDER BY t.orden",
            countQuery = "SELECT count(*) FROM movimiento_insumo m WHERE m.insumo_id = CAST(:insumoId AS uuid) "
            + "AND (CAST(:loteId AS uuid) IS NULL OR m.lote_insumo_id = CAST(:loteId AS uuid)) "
            + "AND (CAST(:desde AS timestamp) IS NULL OR m.creado_en >= CAST(:desde AS timestamp)) "
            + "AND (CAST(:hasta AS timestamp) IS NULL OR m.creado_en < CAST(:hasta AS timestamp))",
            nativeQuery = true)
    Page<Object[]> kardex(@Param("insumoId") UUID insumoId, @Param("loteId") UUID loteId, @Param("desde") LocalDateTime desde,
                          @Param("hasta") LocalDateTime hasta, Pageable pageable);

    // [saldo antes de `desde`, entradas del rango, salidas del rango (positivas)]; con `loteId`, solo de ese lote.
    @Query(value = "SELECT "
            + "COALESCE(SUM(CASE WHEN CAST(:desde AS timestamp) IS NOT NULL AND m.creado_en < CAST(:desde AS timestamp) THEN m.cantidad END), 0), "
            + "COALESCE(SUM(CASE WHEN m.cantidad > 0 AND (CAST(:desde AS timestamp) IS NULL OR m.creado_en >= CAST(:desde AS timestamp)) "
            + "  AND (CAST(:hasta AS timestamp) IS NULL OR m.creado_en < CAST(:hasta AS timestamp)) THEN m.cantidad END), 0), "
            + "COALESCE(SUM(CASE WHEN m.cantidad < 0 AND (CAST(:desde AS timestamp) IS NULL OR m.creado_en >= CAST(:desde AS timestamp)) "
            + "  AND (CAST(:hasta AS timestamp) IS NULL OR m.creado_en < CAST(:hasta AS timestamp)) THEN -m.cantidad END), 0) "
            + "FROM movimiento_insumo m WHERE m.insumo_id = CAST(:insumoId AS uuid) "
            + "AND (CAST(:loteId AS uuid) IS NULL OR m.lote_insumo_id = CAST(:loteId AS uuid))",
            nativeQuery = true)
    List<Object[]> resumen(@Param("insumoId") UUID insumoId, @Param("loteId") UUID loteId, @Param("desde") LocalDateTime desde,
                           @Param("hasta") LocalDateTime hasta);
}
