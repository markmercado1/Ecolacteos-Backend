package com.planta.acopio.repository;

import com.planta.acopio.entity.AsignacionLote;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface AsignacionLoteRepository extends JpaRepository<AsignacionLote, UUID> {

    // Suma de todo lo asignado (lotes + "sin trazabilidad") a movimientos de una presentacion. Para una
    // presentacion controlada: stock del libro = saldo_sin_trazabilidad_inicial + esta suma.
    @Query("SELECT COALESCE(SUM(a.cantidad), 0) FROM AsignacionLote a WHERE a.movimiento.presentacion.id = :presentacionId")
    long sumarPorPresentacion(@Param("presentacionId") UUID presentacionId);

    // Cuantas asignaciones existen en las presentaciones de un producto (0 = ningun movimiento desde la activacion).
    @Query("SELECT COUNT(a) FROM AsignacionLote a WHERE a.movimiento.presentacion.producto.id = :productoId")
    long contarPorProducto(@Param("productoId") UUID productoId);

    // Un lote "usado" es el que ya tiene alguna salida asignada (cantidad negativa).
    boolean existsByLoteDetalleIdAndCantidadLessThan(UUID loteDetalleId, int cantidad);

    // Fase 2: movimiento neto del cajon "sin trazabilidad" (ni lote ni regularizacion). El saldo de ese cajon es
    // saldo_sin_trazabilidad_inicial + esta suma - SUM(regularizaciones) (ver InventarioService).
    @Query("SELECT COALESCE(SUM(a.cantidad), 0) FROM AsignacionLote a WHERE a.movimiento.presentacion.id = :presentacionId "
            + "AND a.loteDetalle IS NULL AND a.regularizacion IS NULL")
    long sumarSinTrazabilidadPorPresentacion(@Param("presentacionId") UUID presentacionId);

    // Fase 2: existencias por lote de una presentacion (lotes de produccion y lotes regularizados con saldo
    // distinto de cero), ya en orden FEFO: vence antes primero; desempate por fecha del lote y por id, para que
    // el orden sea determinista. Columnas: origen ('LOTE'|'REGULARIZACION'), id, lote_id (solo LOTE),
    // fecha_lote (fecha de produccion; para REGULARIZACION, la fecha en que se regularizo), fecha_vencimiento,
    // saldo. Solo debe leerse BAJO el bloqueo de la fila de Presentacion cuando se va a decidir una escritura.
    @Query(value = "SELECT 'LOTE' AS origen, d.id AS id, d.lote_id AS lote_id, l.fecha AS fecha_lote, "
            + "d.fecha_vencimiento AS fecha_vencimiento, SUM(a.cantidad) AS saldo "
            + "FROM movimiento_inventario_lote a "
            + "JOIN movimiento_inventario m ON m.id = a.movimiento_id "
            + "JOIN lote_produccion_detalle d ON d.id = a.lote_detalle_id "
            + "JOIN lote_produccion l ON l.id = d.lote_id "
            + "WHERE m.presentacion_id = :presentacionId "
            + "GROUP BY d.id, d.lote_id, l.fecha, d.fecha_vencimiento "
            + "HAVING SUM(a.cantidad) <> 0 "
            + "UNION ALL "
            + "SELECT 'REGULARIZACION', r.id, CAST(NULL AS uuid), CAST(r.creado_en AS date), r.fecha_vencimiento, "
            + "r.cantidad + COALESCE(SUM(a.cantidad), 0) "
            + "FROM regularizacion_trazabilidad r "
            + "LEFT JOIN movimiento_inventario_lote a ON a.regularizacion_id = r.id "
            + "WHERE r.presentacion_id = :presentacionId "
            + "GROUP BY r.id, r.cantidad, r.creado_en, r.fecha_vencimiento "
            + "HAVING r.cantidad + COALESCE(SUM(a.cantidad), 0) <> 0 "
            + "ORDER BY 5, 4, 2", nativeQuery = true)
    List<Object[]> listarExistenciasPorLote(@Param("presentacionId") UUID presentacionId);
}
