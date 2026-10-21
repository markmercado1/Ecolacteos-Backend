package com.planta.acopio.controller;

import com.planta.acopio.dto.AceptacionPrecioRequest;
import com.planta.acopio.dto.MotivoReservaRequest;
import com.planta.acopio.dto.PaginaResponse;
import com.planta.acopio.dto.RetirarReservaRequest;
import com.planta.acopio.dto.ReservaResponse;
import com.planta.acopio.dto.ReservaResumenResponse;
import com.planta.acopio.dto.VentaResumenResponse;
import com.planta.acopio.service.ReglaVencimiento;
import com.planta.acopio.service.ReservaService;
import com.planta.acopio.service.VentaService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

// Fase 4: gestion de reservas por el personal (VENTAS o ADMIN). El panel web llega en una fase posterior. Confirmar no
// crea una venta: el RETIRO es la venta real, por el flujo normal de ventas (kardex incluido).
@Tag(name = "Reservas", description = "Gestión de reservas. Roles requeridos: VENTAS o ADMIN.")
@RestController
@RequestMapping("/api/reservas")
@PreAuthorize("hasAnyRole('VENTAS','ADMIN')")
public class ReservaController {

    private final ReservaService service;
    private final VentaService ventaService;
    private final ReglaVencimiento regla;

    public ReservaController(ReservaService service, VentaService ventaService, ReglaVencimiento regla) {
        this.service = service;
        this.ventaService = ventaService;
        this.regla = regla;
    }

    @Operation(summary = "Listar reservas (paginado)", description = "Filtros opcionales: `estado` (efectivo: una pendiente pasada de hora o una confirmada "
            + "con la fecha de retiro vencida cuentan como VENCIDA), `fechaRetiro` (yyyy-MM-dd), `telefono` (cualquier formato válido) y `codigo`. Más recientes primero.")
    @GetMapping
    public ResponseEntity<PaginaResponse<ReservaResumenResponse>> listar(
            @Parameter(description = "PENDIENTE, CONFIRMADA, RECHAZADA, CANCELADA, VENCIDA o RETIRADA") @RequestParam(required = false) String estado,
            @Parameter(description = "Fecha de retiro (yyyy-MM-dd)") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fechaRetiro,
            @Parameter(description = "Teléfono del cliente") @RequestParam(required = false) String telefono,
            @Parameter(description = "Código de seguimiento exacto (sin distinguir mayúsculas)") @RequestParam(required = false) String codigo,
            @Parameter(description = "Número de página, empieza en 0") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Tamaño de página (máx. 100)") @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(service.listar(estado, fechaRetiro, telefono, codigo, Paginacion.de(page, size)));
    }

    @Operation(summary = "Detalle de una reserva", description = "Líneas, aceptación de precio, historial y si hoy ya no tiene cobertura de stock.")
    @GetMapping("/{id}")
    public ResponseEntity<ReservaResponse> obtener(@PathVariable UUID id) {
        return ResponseEntity.ok(service.obtener(id));
    }

    @Operation(summary = "Recotizar con el precio público vigente", description = "Solo pendiente. Si algún precio cambia, el cliente debe aceptar de nuevo el importe.")
    @PutMapping("/{id}/recotizar")
    public ResponseEntity<ReservaResponse> recotizar(@PathVariable UUID id) {
        return ResponseEntity.ok(service.recotizar(id));
    }

    @Operation(summary = "Registrar la aceptación del importe por el cliente", description = "Solo pendiente con precio por aceptar y todas las líneas con precio. "
            + "Queda con usuario, fecha, medio y el importe aceptado.")
    @PostMapping("/{id}/aceptacion-precio")
    public ResponseEntity<ReservaResponse> aceptarPrecio(@PathVariable UUID id, @RequestBody @Valid AceptacionPrecioRequest dto) {
        return ResponseEntity.ok(service.registrarAceptacionDePrecio(id, dto));
    }

    @Operation(summary = "Confirmar una reserva", description = "Solo pendiente vigente. Vuelve a comprobar el precio (todas las líneas con precio igual al vigente), "
            + "la aceptación del cliente cuando corresponde y la cobertura de stock. No crea una venta ni mueve el kardex.")
    @PutMapping("/{id}/confirmar")
    public ResponseEntity<ReservaResponse> confirmar(@PathVariable UUID id) {
        return ResponseEntity.ok(service.confirmar(id));
    }

    @Operation(summary = "Rechazar una reserva", description = "Solo pendiente. Motivo obligatorio. Libera las unidades.")
    @PutMapping("/{id}/rechazar")
    public ResponseEntity<ReservaResponse> rechazar(@PathVariable UUID id, @RequestBody @Valid MotivoReservaRequest dto) {
        return ResponseEntity.ok(service.rechazar(id, dto));
    }

    @Operation(summary = "Cancelar una reserva", description = "Pendiente o confirmada. Motivo obligatorio. Libera las unidades.")
    @PutMapping("/{id}/cancelar")
    public ResponseEntity<ReservaResponse> cancelar(@PathVariable UUID id, @RequestBody @Valid MotivoReservaRequest dto) {
        return ResponseEntity.ok(service.cancelar(id, dto));
    }

    @Operation(summary = "Registrar el retiro de una reserva confirmada", description = "Crea la venta real con TODAS las líneas (canal PUBLICO, FEFO, kardex) "
            + "y pasa la reserva a RETIRADA en la misma transacción. Si la venta falla, la reserva sigue confirmada. Para un retiro parcial usa POST /api/ventas/carrito "
            + "con `reservaId`.")
    @PostMapping("/{id}/retirar")
    public ResponseEntity<VentaResumenResponse> retirar(@PathVariable UUID id, @RequestBody @Valid RetirarReservaRequest dto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ventaService.registrarRetiroDeReserva(id, dto.uuidCliente(), regla.hoy()));
    }
}
