package com.planta.acopio.controller;

import com.planta.acopio.dto.AvisoPrivacidadResponse;
import com.planta.acopio.dto.ClienteDtos.CoincidenciasRequest;
import com.planta.acopio.dto.ClienteDtos.CompradorSugerencia;
import com.planta.acopio.service.CompradorService;
import com.planta.acopio.service.PrivacidadService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

// Fase H: busqueda de posibles compradores (clientes y proveedores) y avisos de datos personales.
@Tag(name = "Clientes", description = "Padrón voluntario de compradores, autorizaciones e historial de compras.")
@RestController
public class CompradorController {

    private final CompradorService compradores;
    private final PrivacidadService privacidad;

    public CompradorController(CompradorService compradores, PrivacidadService privacidad) {
        this.compradores = compradores;
        this.privacidad = privacidad;
    }

    @Operation(summary = "Posibles compradores", description = "Roles: ADMIN y VENTAS. Busca fichas de cliente y de proveedor por DNI/RUC exacto, "
            + "teléfono o nombre. Solo SUGIERE: el trabajador confirma con el comprador. Teléfono y documento llegan enmascarados. Se envía por POST "
            + "para no dejar datos personales en la URL.")
    @PostMapping("/api/compradores/sugerencias")
    @PreAuthorize("hasAnyRole('ADMIN','VENTAS')")
    public ResponseEntity<List<CompradorSugerencia>> sugerencias(@RequestBody CoincidenciasRequest dto) {
        return ResponseEntity.ok(compradores.sugerencias(dto));
    }

    @Operation(summary = "Avisos de datos personales (público)", description = "Textos vigentes para la reserva web, el registro de clientes y la "
            + "autorización comercial. `completo` = false mientras falten datos del responsable que exige el reglamento (`faltantes`).")
    @GetMapping("/api/catalogo/privacidad")
    public ResponseEntity<AvisoPrivacidadResponse> avisos() {
        return ResponseEntity.ok(privacidad.avisos());
    }
}
