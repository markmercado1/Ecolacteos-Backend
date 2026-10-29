package com.planta.acopio.controller;

import com.planta.acopio.dto.ProveedorCompraRequest;
import com.planta.acopio.dto.ProveedorCompraResponse;
import com.planta.acopio.service.ProveedorCompraService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

// Proveedores de compra (V42): a quienes se compran insumos y leche. Consulta y alta rapida (nombre + RUC) para PRODUCCION y
// ADMIN, porque se usan al registrar un ingreso; edicion, categorias, insumos habituales y baja, solo ADMIN. No confundir con
// /api/admin/proveedores (ganaderos de leche del acopio).
@Tag(name = "Proveedores de compra", description = "Proveedores de insumos y leche (RUC único, categorías flexibles). Consulta y alta rápida: PRODUCCION o ADMIN; edición y baja: ADMIN.")
@RestController
@RequestMapping("/api/proveedores-compra")
@PreAuthorize("hasAnyRole('PRODUCCION','ADMIN')")
public class ProveedorCompraController {

    private final ProveedorCompraService service;

    public ProveedorCompraController(ProveedorCompraService service) {
        this.service = service;
    }

    @Operation(summary = "Listar proveedores de compra", description = "`busqueda` por nombre o RUC (sin mayúsculas ni tildes). `soloActivos` (por defecto true) para el selector del ingreso.")
    @GetMapping
    public ResponseEntity<List<ProveedorCompraResponse>> listar(@RequestParam(required = false) String busqueda,
                                                                @RequestParam(defaultValue = "true") boolean soloActivos) {
        return ResponseEntity.ok(service.listar(busqueda, soloActivos));
    }

    @Operation(summary = "Categorías disponibles")
    @GetMapping("/categorias")
    public ResponseEntity<List<ProveedorCompraResponse.Categoria>> categorias() {
        return ResponseEntity.ok(service.categorias());
    }

    @GetMapping("/{id}")
    public ResponseEntity<ProveedorCompraResponse> obtener(@PathVariable UUID id) {
        return ResponseEntity.ok(service.obtener(id));
    }

    @Operation(summary = "Crear un proveedor de compra", description = "Solo nombre y RUC son obligatorios; sin categorías queda en «Otros insumos». RUC repetido: 409.")
    @PostMapping
    public ResponseEntity<ProveedorCompraResponse> crear(@RequestBody @Valid ProveedorCompraRequest dto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.crear(dto));
    }

    @Operation(summary = "Editar un proveedor de compra (ADMIN)")
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ProveedorCompraResponse> actualizar(@PathVariable UUID id, @RequestBody @Valid ProveedorCompraRequest dto) {
        return ResponseEntity.ok(service.actualizar(id, dto));
    }

    @Operation(summary = "Desactivar un proveedor de compra (ADMIN)")
    @PatchMapping("/{id}/desactivar")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ProveedorCompraResponse> desactivar(@PathVariable UUID id) {
        return ResponseEntity.ok(service.desactivar(id));
    }

    @Operation(summary = "Reactivar un proveedor de compra (ADMIN)")
    @PatchMapping("/{id}/reactivar")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ProveedorCompraResponse> reactivar(@PathVariable UUID id) {
        return ResponseEntity.ok(service.reactivar(id));
    }
}
