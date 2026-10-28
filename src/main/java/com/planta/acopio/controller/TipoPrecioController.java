package com.planta.acopio.controller;

import com.planta.acopio.dto.TipoPrecioDtos.CambioEstadoRequest;
import com.planta.acopio.dto.TipoPrecioDtos.CrearTipoPrecioRequest;
import com.planta.acopio.dto.TipoPrecioDtos.EditarTipoPrecioRequest;
import com.planta.acopio.dto.TipoPrecioDtos.TipoPrecioResponse;
import com.planta.acopio.service.TipoPrecioService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

// Tipos de precio administrables (V37). Endpoints EXCLUSIVOS de la web; el movil no los usa.
@Tag(name = "Tipos de precio", description = "Catálogo de tipos de precio (MAYORISTA, PROVEEDOR, PUBLICO): consulta y activación.")
@RestController
@RequestMapping("/api/tipos-precio")
public class TipoPrecioController {

    private final TipoPrecioService service;

    public TipoPrecioController(TipoPrecioService service) {
        this.service = service;
    }

    @Operation(summary = "Listar los tipos de precio", description = "Roles: VENTAS, ADMIN. Con soloActivos=true devuelve solo los que se pueden "
            + "usar en operaciones nuevas (los selectores de venta y de precio); sin él, todos con su estado y el último cambio.")
    @GetMapping
    @PreAuthorize("hasAnyRole('VENTAS','ADMIN')")
    public ResponseEntity<List<TipoPrecioResponse>> listar(@RequestParam(defaultValue = "false") boolean soloActivos) {
        return ResponseEntity.ok(service.listar(soloActivos));
    }

    @Operation(summary = "Crear un tipo de precio", description = "Rol requerido: ADMIN. Nombre, código opcional (se deriva del nombre; único y estable), "
            + "regla de comprador (CUALQUIERA, CLIENTE_HABILITADO, PROVEEDOR_PADRON) y motivo. 409 si el código o el nombre ya existen.")
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<TipoPrecioResponse> crear(@Valid @RequestBody CrearTipoPrecioRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.crear(request));
    }

    @Operation(summary = "Editar el nombre y la descripción de un tipo de precio", description = "Rol requerido: ADMIN. El código y la regla de comprador no "
            + "cambian, así que ninguna referencia histórica se altera.")
    @PutMapping("/{codigo}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<TipoPrecioResponse> editar(@PathVariable String codigo, @Valid @RequestBody EditarTipoPrecioRequest request) {
        return ResponseEntity.ok(service.editar(codigo, request));
    }

    @Operation(summary = "Activar o desactivar un tipo de precio", description = "Rol requerido: ADMIN. Motivo obligatorio (queda registrado con quién y "
            + "cuándo). Un tipo desactivado se rechaza en precios y ventas nuevos; lo ya registrado no cambia. PUBLICO no se puede desactivar (400). "
            + "Pedir el estado en el que ya está responde 409.")
    @PutMapping("/{codigo}/estado")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<TipoPrecioResponse> cambiarEstado(@PathVariable String codigo, @Valid @RequestBody CambioEstadoRequest request) {
        return ResponseEntity.ok(service.cambiarEstado(codigo, request.activo(), request.motivo()));
    }
}
