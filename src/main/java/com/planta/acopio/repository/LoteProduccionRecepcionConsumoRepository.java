package com.planta.acopio.repository;

import com.planta.acopio.entity.LoteProduccionRecepcionConsumo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public interface LoteProduccionRecepcionConsumoRepository extends JpaRepository<LoteProduccionRecepcionConsumo, UUID> {

    // Trazabilidad de un lote nuevo (LoteProduccionResponse.recepcionesConsumidas) -- lista vacia para
    // lotes creados antes de esta migracion, que siguen resolviendo su trazabilidad via
    // LoteProduccion.registrosVinculados (lote_registro_acopio), sin cambios.
    List<LoteProduccionRecepcionConsumo> findByLoteProduccionId(UUID loteProduccionId);

    // Saldo remanente de una recepcion puntual: litrosPlanta - esto. Usado por
    // LoteProduccionService.planificarConsumo() SOLO despues de bloquear (FOR UPDATE) la fila de
    // RecepcionPlanta correspondiente -- la lectura en si no necesita bloqueo propio, la consistencia la da
    // el bloqueo ya tomado sobre recepcion_planta antes de llamar aqui.
    @Query("SELECT COALESCE(SUM(c.litrosConsumidos), 0) FROM LoteProduccionRecepcionConsumo c " +
           "WHERE c.recepcionPlanta.id = :recepcionPlantaId")
    BigDecimal sumarLitrosConsumidosPorRecepcion(@Param("recepcionPlantaId") UUID recepcionPlantaId);
}
