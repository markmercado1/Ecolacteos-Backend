package com.planta.acopio.controller;

import com.planta.acopio.dto.JornadaActivaPropiaResponse;
import com.planta.acopio.dto.JornadaBandejaResponse;
import com.planta.acopio.dto.JornadaDetalleResponse;
import com.planta.acopio.service.JornadaService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

// Fase 2 (docs/ESPEC_JORNADA_TRAZABILIDAD.md, Ecolacteos-Frontend): lecturas que RECEPCION necesita para
// elegir una jornada y decidir su recepcion -- la creacion/transicion de Jornada sigue siendo exclusiva del
// motor de sync de ACOPIADOR (POST /api/sync/jornadas*, Fase 1), este controller es solo lectura. No
// duplica RegistroAcopioController: RECEPCION nunca necesita ese permiso amplio, solo los acopios de UNA
// jornada puntual (ver JornadaDetalleResponse).
@Tag(name = "Jornadas (Recepción)", description = "Bandeja y detalle de jornadas REGRESANDO_A_PLANTA. Rol requerido: RECEPCION o ADMIN.")
@RestController
@RequestMapping("/api/jornadas")
@PreAuthorize("hasAnyRole('RECEPCION','ADMIN')")
public class JornadaController {

    private final JornadaService jornadaService;

    public JornadaController(JornadaService jornadaService) {
        this.jornadaService = jornadaService;
    }

    @Operation(
            summary = "Bandeja de jornadas pendientes de recepción",
            description = "Jornadas en estado REGRESANDO_A_PLANTA, ordenadas por hora de inicio (más antigua "
                    + "primero). `litrosEsperados` es SUM(RegistroAcopio.litros) de cada jornada, autoritativo."
    )
    @ApiResponse(responseCode = "200", description = "Jornadas pendientes de recepción",
            content = @Content(schema = @Schema(implementation = JornadaBandejaResponse.class)))
    @GetMapping
    public ResponseEntity<List<JornadaBandejaResponse>> listarPendientesRecepcion() {
        return ResponseEntity.ok(jornadaService.listarPendientesRecepcion());
    }

    @Operation(
            summary = "Detalle de una jornada para recepción",
            description = "Vehículo, acopiador, proveedores visitados, acopios individuales y litros "
                    + "esperados -- todo lo necesario antes de registrar la recepción."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Jornada encontrada", content = @Content(schema = @Schema(implementation = JornadaDetalleResponse.class))),
            @ApiResponse(responseCode = "404", description = "No existe una jornada con ese id")
    })
    @GetMapping("/{id}")
    public ResponseEntity<JornadaDetalleResponse> obtenerDetalle(@PathVariable UUID id) {
        return ResponseEntity.ok(jornadaService.obtenerDetalleParaRecepcion(id));
    }

    // Reconciliacion cliente-servidor (ver SyncEngineImpl.kt del frontend): rol ACOPIADOR, no RECEPCION/ADMIN
    // -- el @PreAuthorize de metodo reemplaza por completo al de la clase para este endpoint (comportamiento
    // estandar de Spring Security, no se "suman"). El acopiador no tiene forma de enterarse de que su propia
    // jornada REGRESANDO_A_PLANTA ya fue RECIBIDA en planta (el modelo movil no modela ese estado a
    // proposito, ver EstadoJornada.kt del frontend) -- este endpoint es la fuente de verdad que su
    // dispositivo consulta para destrabarse solo, sin intervencion manual.
    @Operation(
            summary = "Jornada activa propia (ACOPIADOR)",
            description = "Rol requerido: ACOPIADOR o ADMIN. Devuelve la jornada activa actual del acopiador "
                    + "autenticado tal como la ve el servidor (`activa: null` si no tiene ninguna) -- nunca "
                    + "204, siempre 200 con este wrapper, para que el cliente pueda parsear la ausencia sin "
                    + "confundirla con un error de red."
    )
    @ApiResponse(responseCode = "200", description = "Jornada activa propia, o null si no tiene ninguna",
            content = @Content(schema = @Schema(implementation = JornadaActivaPropiaResponse.class)))
    @GetMapping("/mia/activa")
    @PreAuthorize("hasAnyRole('ACOPIADOR','ADMIN')")
    public ResponseEntity<JornadaActivaPropiaResponse> obtenerMiJornadaActiva() {
        return ResponseEntity.ok(new JornadaActivaPropiaResponse(
                jornadaService.obtenerActivaDelAcopiadorAutenticado().orElse(null)));
    }
}
