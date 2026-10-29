package com.planta.acopio.controller;

import com.planta.acopio.dto.DocumentoIngresoResponse;
import com.planta.acopio.dto.IngresoDocumentoRequest;
import com.planta.acopio.service.IngresoInsumoService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

// Ingreso de insumos con documento de respaldo y lotes (V44). PRODUCCION o ADMIN, como el ingreso por insumo.
@Tag(name = "Ingresos de insumos", description = "Documento de ingreso (proveedor, tipo, número) con una línea por lote. PRODUCCION o ADMIN.")
@RestController
@RequestMapping("/api/inventario/ingresos")
@PreAuthorize("hasAnyRole('PRODUCCION','ADMIN')")
public class IngresoDocumentoController {

    private final IngresoInsumoService service;

    public IngresoDocumentoController(IngresoInsumoService service) {
        this.service = service;
    }

    @Operation(summary = "Registrar un ingreso de insumos con su documento",
            description = "Cada línea crea un lote (código autogenerado si va vacío) y deja un movimiento INGRESO en el kardex. "
                    + "Todo o nada. Reenviar el mismo `uuidCliente` devuelve el ingreso ya registrado; el mismo número de documento "
                    + "del mismo proveedor y tipo responde 409.")
    @PostMapping
    public ResponseEntity<DocumentoIngresoResponse> registrar(@RequestBody @Valid IngresoDocumentoRequest dto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.registrar(dto));
    }

    @Operation(summary = "Consultar un documento de ingreso")
    @GetMapping("/{id}")
    public ResponseEntity<DocumentoIngresoResponse> obtener(@PathVariable UUID id) {
        return ResponseEntity.ok(service.obtener(id));
    }
}
