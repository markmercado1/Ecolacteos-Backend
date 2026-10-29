package com.planta.acopio.repository;

import com.planta.acopio.entity.LoteProduccionInsumo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.UUID;

public interface LoteProduccionInsumoRepository extends JpaRepository<LoteProduccionInsumo, UUID> {
    List<LoteProduccionInsumo> findByLoteId(UUID loteId);

    /**
     * Lotes de insumo de los que salio lo consumido por un lote de produccion (V43): [insumoId, loteId, codigo, codigoProveedor,
     * proveedor, vencimiento, cantidad], en el orden del kardex.
     */
    @Query("SELECT m.insumo.id, l.id, l.codigo, l.codigoProveedor, p.nombre, l.fechaVencimiento, -m.cantidad FROM MovimientoInsumo m "
            + "JOIN m.loteInsumo l LEFT JOIN l.proveedor p WHERE m.loteProduccion.id = :loteProduccionId "
            + "AND m.tipoMovimiento = com.planta.acopio.entity.TipoMovimientoInsumo.SALIDA_PRODUCCION ORDER BY m.orden")
    List<Object[]> lotesDeInsumoUsados(@Param("loteProduccionId") UUID loteProduccionId);
}
