package com.planta.acopio.repository;

import com.planta.acopio.entity.AjusteCalidadEntrega;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AjusteCalidadEntregaRepository extends JpaRepository<AjusteCalidadEntrega, UUID> {

    // El ajuste mas reciente de una entrega es su precio aplicado (el id desempata ajustes del mismo instante).
    Optional<AjusteCalidadEntrega> findFirstByPagoLineaIdOrderByCreadoEnDescIdDesc(UUID pagoLineaId);

    // Varias entregas con UNA consulta, en orden cronologico: el ultimo de cada una pisa a los anteriores.
    List<AjusteCalidadEntrega> findByPagoLineaIdInOrderByCreadoEnAscIdAsc(Collection<UUID> pagoLineaIds);

    // Historial de UNA entrega (una entrega pertenece a lo sumo a un pago): del mas reciente al mas antiguo.
    List<AjusteCalidadEntrega> findByPagoLineaRegistroAcopioIdOrderByCreadoEnDescIdDesc(UUID registroAcopioId);

    // Historial de un pago: del mas reciente al mas antiguo.
    List<AjusteCalidadEntrega> findByPagoLineaPagoIdOrderByCreadoEnDescIdDesc(UUID pagoId);

    // Fase D: en cuantas entregas de un proveedor se fijo un precio de calidad propio.
    @org.springframework.data.jpa.repository.Query("SELECT COUNT(DISTINCT a.pagoLinea.id) FROM AjusteCalidadEntrega a WHERE a.pagoLinea.pago.proveedor.id = :proveedorId")
    long contarEntregasConPrecioPropio(@org.springframework.data.repository.query.Param("proveedorId") UUID proveedorId);
}
