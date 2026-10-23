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

    // Historial de un pago: del mas reciente al mas antiguo.
    List<AjusteCalidadEntrega> findByPagoLineaPagoIdOrderByCreadoEnDescIdDesc(UUID pagoId);
}
