package com.planta.acopio.controller;

import com.planta.acopio.dto.CerrarJornadaForzadoRequest;
import com.planta.acopio.dto.JornadaBandejaResponse;
import com.planta.acopio.dto.JornadaResponse;
import com.planta.acopio.service.JornadaService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

// Herramienta de soporte, fuera del flujo normal ACOPIADOR -> RECEPCION: una Jornada puede quedar
// "huerfana" -- EN_RECOLECCION cuyo FINALIZAR nunca se sincronizo, o REGRESANDO_A_PLANTA que nadie recibio
// en planta -- por ejemplo si el dispositivo del acopiador se reinstalo o cambio de cuenta antes de
// sincronizar esa transicion pendiente. Mientras siga activa, el indice unico de V3__jornada.sql bloquea
// CUALQUIER jornada nueva del mismo acopiador/unidad con 409 "Ya existe una jornada activa", y si quedo en
// EN_RECOLECCION, RECEPCION ni siquiera puede verla (GET /api/jornadas solo lista REGRESANDO_A_PLANTA).
// Este controller le da a un ADMIN visibilidad total y una salida manual, sin tocar la base de datos a mano.
@Tag(name = "Jornadas (Admin)", description = "Diagnóstico y cierre forzado de jornadas huérfanas. Rol requerido: ADMIN.")
@RestController
@RequestMapping("/api/admin/jornadas")
@PreAuthorize("hasRole('ADMIN')")
public class AdminJornadaController {

    private final JornadaService jornadaService;

    public AdminJornadaController(JornadaService jornadaService) {
        this.jornadaService = jornadaService;
    }

    @Operation(
            summary = "Listar todas las jornadas activas",
            description = "Rol requerido: ADMIN. A diferencia de GET /api/jornadas (solo REGRESANDO_A_PLANTA, "
                    + "para la bandeja de RECEPCIÓN), esta lista incluye también EN_RECOLECCION -- es la forma "
                    + "de encontrar la jornada que está bloqueando a un acopiador con el conflicto 409 'ya "
                    + "existe una jornada activa', incluso si RECEPCIÓN no la ve en su bandeja."
    )
    @ApiResponse(responseCode = "200", description = "Jornadas activas (EN_RECOLECCION o REGRESANDO_A_PLANTA), más antigua primero",
            content = @Content(array = @ArraySchema(schema = @Schema(implementation = JornadaBandejaResponse.class))))
    @GetMapping("/activas")
    public ResponseEntity<List<JornadaBandejaResponse>> listarActivas() {
        return ResponseEntity.ok(jornadaService.listarTodasActivas());
    }

    @Operation(
            summary = "Cerrar forzadamente una jornada huérfana",
            description = "Rol requerido: ADMIN. La pasa a CANCELADA sin importar su estado actual (EN_RECOLECCION "
                    + "o REGRESANDO_A_PLANTA) ni si ya tiene acopios asociados -- a diferencia de la cancelación "
                    + "normal del acopiador, que exige EN_RECOLECCION sin acopios. Libera de inmediato el cupo de "
                    + "acopiador/vehículo para una jornada nueva. Los RegistroAcopio ya capturados NO se tocan ni "
                    + "se eliminan, solo quedan sin una jornada activa asociada. Idempotente: si la jornada ya "
                    + "estaba RECIBIDA o CANCELADA, no hace nada y devuelve su estado actual."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Jornada cerrada (o ya estaba cerrada)",
                    content = @Content(schema = @Schema(implementation = JornadaResponse.class))),
            @ApiResponse(responseCode = "404", description = "No existe una jornada con ese id")
    })
    @PatchMapping("/{id}/cerrar-forzado")
    public ResponseEntity<JornadaResponse> cerrarForzado(
            @Parameter(description = "Id de la jornada") @PathVariable UUID id,
            @RequestBody(required = false) CerrarJornadaForzadoRequest dto) {
        String motivo = dto != null ? dto.motivo() : null;
        return ResponseEntity.ok(jornadaService.cerrarForzado(id, motivo));
    }
}
