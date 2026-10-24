package com.planta.acopio.controller;

import com.planta.acopio.dto.AnularPagoProveedorRequest;
import com.planta.acopio.dto.AplicarPagoProveedorRequest;
import com.planta.acopio.dto.PaginaResponse;
import com.planta.acopio.dto.PagoProveedorRequest;
import com.planta.acopio.dto.PagoProveedorResponse;
import com.planta.acopio.dto.SaldoPagoResponse;
import com.planta.acopio.dto.SaldoProveedorResponse;
import com.planta.acopio.exception.ValidacionNegocioException;
import com.planta.acopio.service.PagoProveedorService;
import com.planta.acopio.service.SaldoPagoService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

// Pagos efectivos (decision confirmada): ADMIN y RECEPCION registran, aplican y consultan; anular es solo de ADMIN. Ninguna respuesta
// trae DNI ni telefono del proveedor.
@Tag(name = "Pagos a proveedores", description = "Dinero realmente entregado a los proveedores y su aplicacion a las liquidaciones. "
        + "Registrar, aplicar y consultar: ADMIN y RECEPCION. Anular: solo ADMIN.")
@RestController
@RequestMapping("/api/pagos-proveedor")
public class PagoProveedorController {

    private static final int MAXIMO_SALDOS = 100;

    private final PagoProveedorService service;
    private final SaldoPagoService saldoService;

    public PagoProveedorController(PagoProveedorService service, SaldoPagoService saldoService) {
        this.service = service;
        this.saldoService = saldoService;
    }

    @Operation(
            summary = "Registrar un pago a un proveedor",
            description = "Roles: ADMIN y RECEPCION. `uuidCliente` lo genera quien registra: reenviar la misma solicitud (o la misma operacion "
                    + "bancaria con los mismos datos) devuelve el pago ya registrado con 200, no crea otro. Transferencia y deposito exigen "
                    + "`banco` y `nroOperacion`. `aplicaciones` es opcional: sin ellas queda sin aplicar (adelanto). Se rechaza (409) aplicar mas de "
                    + "lo que queda del pago o del saldo de una liquidacion; todo se guarda o nada."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Pago registrado", content = @Content(schema = @Schema(implementation = PagoProveedorResponse.class))),
            @ApiResponse(responseCode = "200", description = "Reenvio: el pago ya estaba registrado"),
            @ApiResponse(responseCode = "400", description = "Datos invalidos (monto, modalidad sin banco/operacion, liquidacion de otro proveedor)"),
            @ApiResponse(responseCode = "404", description = "El proveedor o una liquidacion no existe"),
            @ApiResponse(responseCode = "409", description = "El identificador o la operacion bancaria ya se usaron con otros datos, o se supera un saldo")
    })
    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','RECEPCION')")
    public ResponseEntity<PagoProveedorResponse> registrar(@Valid @RequestBody PagoProveedorRequest dto) {
        PagoProveedorService.Resultado r = service.registrar(dto);
        return ResponseEntity.status(r.creado() ? HttpStatus.CREATED : HttpStatus.OK).body(r.pago());
    }

    @Operation(
            summary = "Listar los pagos a proveedores (paginado)",
            description = "Roles: ADMIN y RECEPCION. Filtros opcionales: `proveedorId`, `estado` (REGISTRADO o ANULADO) y `busqueda` (proveedor, DNI, "
                    + "numero de operacion o de recibo; sin acentos ni mayusculas). Mas reciente primero."
    )
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','RECEPCION')")
    public ResponseEntity<PaginaResponse<PagoProveedorResponse>> listar(
            @RequestParam(required = false) UUID proveedorId,
            @RequestParam(required = false) String estado,
            @RequestParam(required = false) String busqueda,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(PaginaResponse.de(service.listar(proveedorId, estado, busqueda, Paginacion.de(page, size))));
    }

    @Operation(summary = "Saldos de varias liquidaciones", description = "Roles: ADMIN y RECEPCION. `pagoIds` separados por coma (hasta "
            + MAXIMO_SALDOS + "). Saldo = total a pagar + ajustes de litros - aplicaciones vigentes; negativo = a favor del proveedor.")
    @GetMapping("/saldos")
    @PreAuthorize("hasAnyRole('ADMIN','RECEPCION')")
    public ResponseEntity<List<SaldoPagoResponse>> saldos(@Parameter(description = "Ids de las liquidaciones, separados por coma") @RequestParam List<UUID> pagoIds) {
        if (pagoIds.isEmpty() || pagoIds.size() > MAXIMO_SALDOS) {
            throw new ValidacionNegocioException("Indique entre 1 y " + MAXIMO_SALDOS + " liquidaciones");
        }
        return ResponseEntity.ok(saldoService.deLosPagos(pagoIds));
    }

    @Operation(summary = "Saldo de un proveedor", description = "Roles: ADMIN y RECEPCION. Saldo de cada liquidacion, adelantos sin aplicar y saldo neto.")
    @GetMapping("/proveedor/{proveedorId}/saldo")
    @PreAuthorize("hasAnyRole('ADMIN','RECEPCION')")
    public ResponseEntity<SaldoProveedorResponse> saldoDelProveedor(@PathVariable UUID proveedorId) {
        return ResponseEntity.ok(saldoService.delProveedor(proveedorId));
    }

    @Operation(summary = "Detalle de un pago a proveedor", description = "Roles: ADMIN y RECEPCION.")
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','RECEPCION')")
    public ResponseEntity<PagoProveedorResponse> obtener(@PathVariable UUID id) {
        return ResponseEntity.ok(service.obtener(id));
    }

    @Operation(
            summary = "Aplicar un pago a liquidaciones",
            description = "Roles: ADMIN y RECEPCION. Aplica (parte de) lo que queda de un pago a una o varias liquidaciones del MISMO proveedor "
                    + "(p. ej. un adelanto). 409 si el pago esta anulado, o si se supera lo que le queda o el saldo de una liquidacion."
    )
    @PostMapping("/{id}/aplicaciones")
    @PreAuthorize("hasAnyRole('ADMIN','RECEPCION')")
    public ResponseEntity<PagoProveedorResponse> aplicar(@PathVariable UUID id, @Valid @RequestBody AplicarPagoProveedorRequest dto) {
        return ResponseEntity.ok(service.aplicar(id, dto));
    }

    @Operation(
            summary = "Anular un pago a proveedor",
            description = "Rol requerido: ADMIN. Conserva la fila y sus aplicaciones (que dejan de contar en los saldos); el motivo es obligatorio. "
                    + "409 si ya estaba anulado."
    )
    @PostMapping("/{id}/anular")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<PagoProveedorResponse> anular(@PathVariable UUID id, @Valid @RequestBody AnularPagoProveedorRequest dto) {
        return ResponseEntity.ok(service.anular(id, dto.motivo()));
    }
}
