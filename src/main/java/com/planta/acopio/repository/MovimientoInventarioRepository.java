package com.planta.acopio.repository;

import com.planta.acopio.entity.MovimientoInventario;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface MovimientoInventarioRepository extends JpaRepository<MovimientoInventario, UUID> {
    java.util.Optional<MovimientoInventario> findBySalidaId(UUID salidaId);


    // El stock nunca se guarda como valor mutable -- siempre se calcula como SUM(cantidad) agrupado por
    // presentacion_id (ver InventarioService). COALESCE cubre el caso de una presentacion sin movimientos.
    // Fase 3: reemplaza sumarCantidadPorProducto -- el stock se calcula por PRESENTACION, no por producto.
    @Query("SELECT COALESCE(SUM(m.cantidad), 0) FROM MovimientoInventario m WHERE m.presentacion.id = :presentacionId")
    long sumarCantidadPorPresentacion(@Param("presentacionId") UUID presentacionId);

    // Kardex de producto terminado por presentacion (V19, solo lectura del libro existente). Mismo contrato
    // de columnas y criterio de saldo acumulado que MovimientoInsumoRepository.kardex(). Documento: el lote que
    // la produjo, la venta que la despacho o el ajuste por conteo. Solo cantidades: no expone precios de venta
    // (el precio no es costo y el kardex no esta valorizado).
    @Query(value = "SELECT t.id, t.creado_en, t.tipo_movimiento, t.cantidad, t.saldo, t.documento_tipo, "
            + "t.documento_id, t.documento_fecha, t.referencia, t.usuario_nombre, t.motivo FROM ("
            + "  SELECT m.id, m.orden, m.creado_en, m.tipo_movimiento, m.cantidad, "
            + "    SUM(m.cantidad) OVER (ORDER BY m.orden) AS saldo, "
            + "    CASE m.tipo_movimiento WHEN 'ENTRADA_PRODUCCION' THEN 'LOTE' WHEN 'SALIDA_VENTA' THEN 'VENTA' WHEN 'AJUSTE' THEN 'AJUSTE' ELSE 'SALIDA' END AS documento_tipo, "
            + "    COALESCE(l.id, v.id, a.id, s.id) AS documento_id, "
            + "    COALESCE(l.fecha, v.fecha, CAST(a.creado_en AS date), CAST(s.creado_en AS date)) AS documento_fecha, "
            + "    CASE m.tipo_movimiento WHEN 'ENTRADA_PRODUCCION' THEN 'Lote de producción' "
            + "      WHEN 'SALIDA_VENTA' THEN CONCAT('Venta · ', v.tipo_cliente) "
            + "      WHEN 'AJUSTE' THEN CONCAT('Conteo físico: ', a.cantidad_anterior, ' → ', a.cantidad_corregida) ELSE CONCAT(s.origen_lote, ' ', s.lote_id, ' · Vencimiento: ', s.fecha_vencimiento) END AS referencia, "
            + "    COALESCE(ul.nombre, uv.nombre, ua.nombre, us.nombre) AS usuario_nombre, COALESCE(a.motivo, s.motivo) AS motivo "
            + "  FROM movimiento_inventario m "
            + "  LEFT JOIN lote_produccion_detalle d ON d.id = m.lote_produccion_detalle_id "
            + "  LEFT JOIN lote_produccion l ON l.id = d.lote_id "
            + "  LEFT JOIN usuario ul ON ul.id = l.responsable_id "
            + "  LEFT JOIN venta_detalle vd ON vd.id = m.venta_detalle_id "
            + "  LEFT JOIN venta v ON v.id = vd.venta_id "
            + "  LEFT JOIN usuario uv ON uv.id = v.vendedor_id "
            + "  LEFT JOIN ajuste_inventario a ON a.id = m.ajuste_inventario_id "
            + "  LEFT JOIN usuario ua ON ua.id = a.usuario_id "
            + "  LEFT JOIN salida_inventario s ON s.id = m.salida_id "
            + "  LEFT JOIN usuario us ON us.id = s.usuario_id "
            + "  WHERE m.presentacion_id = CAST(:presentacionId AS uuid)"
            + ") t "
            + "WHERE (CAST(:desde AS timestamp) IS NULL OR t.creado_en >= CAST(:desde AS timestamp)) "
            + "AND (CAST(:hasta AS timestamp) IS NULL OR t.creado_en < CAST(:hasta AS timestamp)) "
            + "ORDER BY t.orden",
            countQuery = "SELECT count(*) FROM movimiento_inventario m WHERE m.presentacion_id = CAST(:presentacionId AS uuid) "
            + "AND (CAST(:desde AS timestamp) IS NULL OR m.creado_en >= CAST(:desde AS timestamp)) "
            + "AND (CAST(:hasta AS timestamp) IS NULL OR m.creado_en < CAST(:hasta AS timestamp))",
            nativeQuery = true)
    Page<Object[]> kardex(@Param("presentacionId") UUID presentacionId, @Param("desde") LocalDateTime desde,
                          @Param("hasta") LocalDateTime hasta, Pageable pageable);

    // [saldo antes de `desde`, entradas del rango, salidas del rango (positivas)].
    @Query(value = "SELECT "
            + "COALESCE(SUM(CASE WHEN CAST(:desde AS timestamp) IS NOT NULL AND m.creado_en < CAST(:desde AS timestamp) THEN m.cantidad END), 0), "
            + "COALESCE(SUM(CASE WHEN m.cantidad > 0 AND (CAST(:desde AS timestamp) IS NULL OR m.creado_en >= CAST(:desde AS timestamp)) "
            + "  AND (CAST(:hasta AS timestamp) IS NULL OR m.creado_en < CAST(:hasta AS timestamp)) THEN m.cantidad END), 0), "
            + "COALESCE(SUM(CASE WHEN m.cantidad < 0 AND (CAST(:desde AS timestamp) IS NULL OR m.creado_en >= CAST(:desde AS timestamp)) "
            + "  AND (CAST(:hasta AS timestamp) IS NULL OR m.creado_en < CAST(:hasta AS timestamp)) THEN -m.cantidad END), 0) "
            + "FROM movimiento_inventario m WHERE m.presentacion_id = CAST(:presentacionId AS uuid)",
            nativeQuery = true)
    List<Object[]> resumen(@Param("presentacionId") UUID presentacionId, @Param("desde") LocalDateTime desde,
                           @Param("hasta") LocalDateTime hasta);
}
