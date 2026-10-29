package com.planta.acopio.controller;

import com.planta.acopio.dto.PlanLotesProduccionResponse;
import com.planta.acopio.entity.Insumo;
import com.planta.acopio.entity.LoteInsumo;
import com.planta.acopio.entity.LoteProduccionInsumo;
import com.planta.acopio.exception.RecursoNoEncontradoException;
import com.planta.acopio.repository.LoteProduccionInsumoRepository;
import com.planta.acopio.repository.LoteProduccionRepository;
import com.planta.acopio.service.LoteInsumoService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

// Plan de lotes de insumo de una produccion planificada (V43): que lotes se usarian en FEFO y cuales hay para cambiarlos a mano al iniciar.
// Solo lectura; PRODUCCION o ADMIN, como iniciar la produccion.
@Tag(name = "Lotes de producción", description = "Plan de lotes de insumo de una producción planificada (solo lectura).")
@RestController
@RequestMapping("/api/lotes-produccion")
@PreAuthorize("hasAnyRole('PRODUCCION','ADMIN')")
public class PlanLotesProduccionController {

    private final LoteProduccionRepository loteProduccionRepository;
    private final LoteProduccionInsumoRepository planRepository;
    private final LoteInsumoService loteInsumoService;

    public PlanLotesProduccionController(LoteProduccionRepository loteProduccionRepository, LoteProduccionInsumoRepository planRepository,
                                         LoteInsumoService loteInsumoService) {
        this.loteProduccionRepository = loteProduccionRepository;
        this.planRepository = planRepository;
        this.loteInsumoService = loteInsumoService;
    }

    @Operation(summary = "Plan de lotes de insumo de una producción planificada",
            description = "Por insumo: lo que pide el plan, el reparto FEFO que se haría y los lotes consumibles (con existencia y no vencidos) para elegir a mano al iniciar.")
    @GetMapping("/{id}/plan-lotes")
    @Transactional(readOnly = true)
    public ResponseEntity<PlanLotesProduccionResponse> planLotes(@PathVariable UUID id) {
        if (!loteProduccionRepository.existsById(id)) {
            throw new RecursoNoEncontradoException("Lote de producción no encontrado: " + id);
        }
        List<PlanLotesProduccionResponse.Insumo> insumos = planRepository.findByLoteId(id).stream().map(this::deInsumo).toList();
        return ResponseEntity.ok(new PlanLotesProduccionResponse(id, insumos));
    }

    private PlanLotesProduccionResponse.Insumo deInsumo(LoteProduccionInsumo fila) {
        Insumo i = fila.getInsumo();
        List<PlanLotesProduccionResponse.Parte> propuesta = loteInsumoService.proponer(i, fila.getCantidad()).stream()
                .map(a -> new PlanLotesProduccionResponse.Parte(a.lote().getId(), a.lote().getCodigo(), a.lote().getFechaVencimiento(), a.cantidad())).toList();
        List<PlanLotesProduccionResponse.Disponible> disponibles = loteInsumoService.consumibles(i).stream().map(this::deLote).toList();
        return new PlanLotesProduccionResponse.Insumo(i.getId(), i.getNombre(), i.getUnidadMedida(), fila.getCantidad(), propuesta, disponibles);
    }

    private PlanLotesProduccionResponse.Disponible deLote(LoteInsumo l) {
        return new PlanLotesProduccionResponse.Disponible(l.getId(), l.getCodigo(), l.getProveedor() == null ? null : l.getProveedor().getNombre(),
                l.getFechaVencimiento(), l.getCantidadDisponible());
    }
}
