package com.planta.acopio.controller;

import com.planta.acopio.dto.HistorialEntregaResponse;
import com.planta.acopio.dto.HistorialResumenResponse;
import com.planta.acopio.dto.PaginaResponse;
import com.planta.acopio.service.HistorialProveedorService;
import com.planta.acopio.service.HistorialProveedorService.Secciones;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

// Fase D: historial consolidado de un proveedor (solo lectura). ADMIN, RECEPCION y CALIDAD consultan, pero cada uno ve solo SUS secciones:
// calidad -> ADMIN y CALIDAD; pagos -> ADMIN y RECEPCION; zonas -> ADMIN; entregas -> todos. Una seccion no autorizada llega nula.
@Tag(name = "Historial del proveedor", description = "Resumen y entregas consolidados (zonas, litros, calidad, liquidaciones, pagos y saldos). "
        + "Solo lectura. ADMIN, RECEPCION y CALIDAD; cada rol ve solo sus secciones.")
@RestController
@RequestMapping("/api/proveedores/{proveedorId}/historial")
public class HistorialProveedorController {

    private final HistorialProveedorService service;
    private final com.planta.acopio.service.HistorialComprasService compras;

    public HistorialProveedorController(HistorialProveedorService service, com.planta.acopio.service.HistorialComprasService compras) {
        this.service = service;
        this.compras = compras;
    }

    @Operation(summary = "Resumen consolidado del proveedor",
            description = "Roles: ADMIN, RECEPCION y CALIDAD. `secciones` indica cuales llegaron: ENTREGAS (todos), CALIDAD (ADMIN y CALIDAD), "
                    + "PAGOS (ADMIN y RECEPCION) y ZONAS (ADMIN). Los totales salen de las mismas fuentes que las demas pantallas y cuadran con ellas. Sin DNI ni telefono.")
    @GetMapping("/resumen")
    @PreAuthorize("hasAnyRole('ADMIN','RECEPCION','CALIDAD')")
    public ResponseEntity<HistorialResumenResponse> resumen(@PathVariable UUID proveedorId, Authentication autenticacion) {
        return ResponseEntity.ok(service.resumen(proveedorId, secciones(autenticacion)));
    }

    @Operation(summary = "Entregas del proveedor con zona, calidad y liquidacion (paginado)",
            description = "Roles: ADMIN, RECEPCION y CALIDAD. Mas reciente primero. `calidad` solo ADMIN y CALIDAD; `liquidacion` solo ADMIN y RECEPCION "
                    + "(nula si la entrega aun no esta liquidada). La zona es la que tenia el proveedor cuando entrego.")
    @GetMapping("/entregas")
    @PreAuthorize("hasAnyRole('ADMIN','RECEPCION','CALIDAD')")
    public ResponseEntity<PaginaResponse<HistorialEntregaResponse>> entregas(
            @PathVariable UUID proveedorId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Authentication autenticacion) {
        return ResponseEntity.ok(PaginaResponse.de(service.entregas(proveedorId, Paginacion.de(page, size), secciones(autenticacion))));
    }

    // Fase H: lo que el proveedor compro (ventas en las que se eligio su ficha como comprador). Solo ADMIN. No toca sus liquidaciones.
    @Operation(summary = "Compras del proveedor (paginado)", description = "Rol: ADMIN. Ventas en las que el proveedor fue el comprador, con canal, "
            + "líneas y reserva de origen; más reciente primero.")
    @GetMapping("/compras")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<PaginaResponse<com.planta.acopio.dto.ClienteDtos.CompraResponse>> compras(
            @PathVariable UUID proveedorId, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        service.resumen(proveedorId, new Secciones(false, false, false)); // 404 si el proveedor no existe
        return ResponseEntity.ok(compras.compras(com.planta.acopio.entity.TipoComprador.PROVEEDOR, proveedorId, Paginacion.de(page, size)));
    }

    static Secciones secciones(Authentication autenticacion) {
        boolean admin = tiene(autenticacion, "ROLE_ADMIN");
        return new Secciones(admin || tiene(autenticacion, "ROLE_CALIDAD"), admin || tiene(autenticacion, "ROLE_RECEPCION"), admin, admin);
    }

    private static boolean tiene(Authentication autenticacion, String rol) {
        for (GrantedAuthority a : autenticacion.getAuthorities()) {
            if (rol.equals(a.getAuthority())) return true;
        }
        return false;
    }
}
