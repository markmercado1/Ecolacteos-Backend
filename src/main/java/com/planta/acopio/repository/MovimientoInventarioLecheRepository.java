package com.planta.acopio.repository;

import com.planta.acopio.entity.MovimientoInventarioLeche;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.query.Param;

public interface MovimientoInventarioLecheRepository extends JpaRepository<MovimientoInventarioLeche, UUID> {

    // Saldo de leche disponible en planta: nunca un valor mutable -- siempre SUM(cantidad) de todo el libro
    // mayor (mismo criterio que MovimientoInventarioRepository.sumarCantidadPorTipoQueso). A diferencia del
    // queso, la leche es un unico pool de planta, no se agrupa por tipo -- COALESCE cubre planta sin
    // movimientos todavia.
    @Query("SELECT COALESCE(SUM(m.cantidad), 0) FROM MovimientoInventarioLeche m")
    BigDecimal sumarLitrosDisponibles();

    // Kardex de leche (V19, solo lectura del libro existente). Mismo contrato de columnas y criterio de saldo
    // acumulado que MovimientoInsumoRepository.kardex(). Documento: la recepcion que la ingreso o el lote que
    // la consumio.
    @Query(value = "SELECT t.id, t.creado_en, t.tipo_movimiento, t.cantidad, t.saldo, t.documento_tipo, "
            + "t.documento_id, t.documento_fecha, t.referencia, t.usuario_nombre, t.motivo FROM ("
            + "  SELECT m.id, m.creado_en, m.tipo_movimiento, m.cantidad, "
            + "    SUM(m.cantidad) OVER (ORDER BY m.creado_en, m.id) AS saldo, "
            + "    CASE WHEN m.recepcion_planta_id IS NOT NULL THEN 'RECEPCION' ELSE 'LOTE' END AS documento_tipo, "
            + "    COALESCE(m.recepcion_planta_id, m.lote_produccion_id) AS documento_id, "
            + "    COALESCE(r.fecha, l.fecha) AS documento_fecha, "
            + "    CASE WHEN r.id IS NOT NULL THEN CONCAT('Recepción · unidad ', un.placa, ' · turno ', r.turno) "
            + "         ELSE CONCAT('Lote de producción · ', p.nombre) END AS referencia, "
            + "    COALESCE(ur.nombre, ul.nombre) AS usuario_nombre, CAST(NULL AS varchar) AS motivo "
            + "  FROM movimiento_inventario_leche m "
            + "  LEFT JOIN recepcion_planta r ON r.id = m.recepcion_planta_id "
            + "  LEFT JOIN unidad un ON un.id = r.unidad_id "
            + "  LEFT JOIN usuario ur ON ur.id = r.responsable_id "
            + "  LEFT JOIN lote_produccion l ON l.id = m.lote_produccion_id "
            + "  LEFT JOIN producto p ON p.id = l.producto_id "
            + "  LEFT JOIN usuario ul ON ul.id = l.responsable_id"
            + ") t "
            + "WHERE (CAST(:desde AS timestamp) IS NULL OR t.creado_en >= CAST(:desde AS timestamp)) "
            + "AND (CAST(:hasta AS timestamp) IS NULL OR t.creado_en < CAST(:hasta AS timestamp)) "
            + "ORDER BY t.creado_en, t.id",
            countQuery = "SELECT count(*) FROM movimiento_inventario_leche m "
            + "WHERE (CAST(:desde AS timestamp) IS NULL OR m.creado_en >= CAST(:desde AS timestamp)) "
            + "AND (CAST(:hasta AS timestamp) IS NULL OR m.creado_en < CAST(:hasta AS timestamp))",
            nativeQuery = true)
    Page<Object[]> kardex(@Param("desde") LocalDateTime desde, @Param("hasta") LocalDateTime hasta, Pageable pageable);

    // [saldo antes de `desde`, entradas del rango, salidas del rango (positivas)].
    @Query(value = "SELECT "
            + "COALESCE(SUM(CASE WHEN CAST(:desde AS timestamp) IS NOT NULL AND m.creado_en < CAST(:desde AS timestamp) THEN m.cantidad END), 0), "
            + "COALESCE(SUM(CASE WHEN m.cantidad > 0 AND (CAST(:desde AS timestamp) IS NULL OR m.creado_en >= CAST(:desde AS timestamp)) "
            + "  AND (CAST(:hasta AS timestamp) IS NULL OR m.creado_en < CAST(:hasta AS timestamp)) THEN m.cantidad END), 0), "
            + "COALESCE(SUM(CASE WHEN m.cantidad < 0 AND (CAST(:desde AS timestamp) IS NULL OR m.creado_en >= CAST(:desde AS timestamp)) "
            + "  AND (CAST(:hasta AS timestamp) IS NULL OR m.creado_en < CAST(:hasta AS timestamp)) THEN -m.cantidad END), 0) "
            + "FROM movimiento_inventario_leche m",
            nativeQuery = true)
    List<Object[]> resumen(@Param("desde") LocalDateTime desde, @Param("hasta") LocalDateTime hasta);
}
