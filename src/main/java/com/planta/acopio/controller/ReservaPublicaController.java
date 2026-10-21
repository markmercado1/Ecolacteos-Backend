package com.planta.acopio.controller;

import com.planta.acopio.dto.ConsultaReservaRequest;
import com.planta.acopio.dto.CrearReservaRequest;
import com.planta.acopio.dto.ReservaPublicaResponse;
import com.planta.acopio.service.ReservaService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// Fase 4: reservas PUBLICAS (sin JWT, ver SecurityConfig). Solo dos operaciones: crear una reserva y consultar la PROPIA
// (codigo + telefono). El cliente nunca cambia estados ni ve datos de otros clientes; confirmar, rechazar, cancelar y
// retirar son del personal (ReservaController).
@Tag(name = "Reservas públicas", description = "Crear y consultar la propia reserva. Sin autenticación.")
@RestController
@RequestMapping("/api/catalogo/reservas")
public class ReservaPublicaController {

    private final ReservaService service;

    public ReservaPublicaController(ReservaService service) {
        this.service = service;
    }

    @Operation(summary = "Crear una reserva (pública)", description = "Pública. Compromete las unidades (sin reducir el stock físico ni crear una venta) hasta "
            + "que el personal la atienda o venza (24 h por defecto). Valida en el servidor: teléfono peruano (9 dígitos, con o sin +51), fecha de "
            + "retiro dentro del plazo y en día de atención, máximo de unidades por reserva, máximo de reservas pendientes por teléfono y disponibilidad "
            + "real (lotes vendibles que lleguen a la fecha de retiro). Idempotente por `uuidCliente`. Si alguna presentación no tiene precio público, "
            + "`total` es null y `precioAConfirmar` true.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Reserva registrada", content = @Content(schema = @Schema(implementation = ReservaPublicaResponse.class))),
            @ApiResponse(responseCode = "400", description = "Datos inválidos, fuera de plazo, sin disponibilidad o límite excedido"),
            @ApiResponse(responseCode = "409", description = "Demasiadas reservas pendientes para ese teléfono, o uuidCliente repetido con otros datos"),
            @ApiResponse(responseCode = "422", description = "La presentación no está disponible para reservar")
    })
    @PostMapping
    public ResponseEntity<ReservaPublicaResponse> crear(@RequestBody @Valid CrearReservaRequest dto) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(service.crear(dto));
    }

    @Operation(summary = "Reglas vigentes para reservar (pública)", description = "Pública, solo lectura. Fechas de retiro que el servidor aceptaría ahora "
            + "(plazo, días de atención, feriados y hora de cierre ya aplicados, hora de Lima), máximo de unidades por reserva y horas que una solicitud "
            + "pendiente retiene las unidades. Informativo: la validación autoritativa ocurre al crear la reserva.")
    @GetMapping("/reglas")
    public ResponseEntity<com.planta.acopio.dto.ReglasReservaPublicaResponse> reglas() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.reglasPublicas());
    }

    @Operation(summary = "Consultar la propia reserva (pública)", description = "Pública. Requiere el código recibido y el teléfono de la reserva; "
            + "cualquier desajuste responde 404 igual que un código inexistente.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Estado de la reserva", content = @Content(schema = @Schema(implementation = ReservaPublicaResponse.class))),
            @ApiResponse(responseCode = "404", description = "No existe una reserva con ese código y teléfono")
    })
    @PostMapping("/consulta")
    public ResponseEntity<ReservaPublicaResponse> consultar(@RequestBody @Valid ConsultaReservaRequest dto) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.consultar(dto));
    }
}
