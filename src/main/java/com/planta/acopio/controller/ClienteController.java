package com.planta.acopio.controller;

import com.planta.acopio.dto.ClienteDtos.ClienteRequest;
import com.planta.acopio.dto.ClienteDtos.ClienteResponse;
import com.planta.acopio.dto.ClienteDtos.ClienteUpdateRequest;
import com.planta.acopio.dto.ClienteDtos.ComercialRequest;
import com.planta.acopio.dto.ClienteDtos.CompraResponse;
import com.planta.acopio.dto.ClienteDtos.ConsentimientoResponse;
import com.planta.acopio.dto.ClienteDtos.FusionRequest;
import com.planta.acopio.dto.ClienteDtos.FusionResponse;
import com.planta.acopio.dto.ClienteDtos.HistorialClienteResponse;
import com.planta.acopio.dto.ClienteDtos.MayoristaRequest;
import com.planta.acopio.dto.ClienteDtos.TelefonoRequest;
import com.planta.acopio.dto.PaginaResponse;
import com.planta.acopio.entity.TipoComprador;
import com.planta.acopio.service.ClienteService;
import com.planta.acopio.service.HistorialComprasService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

// Fase H: padron VOLUNTARIO de clientes. Exclusivo de la web (el movil no lo usa). ADMIN ve los datos completos; VENTAS registra,
// consulta (telefonos, DNI, RUC y correo enmascarados), agrega telefonos y registra o revoca la autorizacion comercial. Solo ADMIN
// edita documentos, habilita mayoristas, desactiva y fusiona.
@Tag(name = "Clientes", description = "Padrón voluntario de compradores, autorizaciones e historial de compras.")
@RestController
@RequestMapping("/api/clientes")
public class ClienteController {

    private final ClienteService service;
    private final HistorialComprasService historial;

    public ClienteController(ClienteService service, HistorialComprasService historial) {
        this.service = service;
        this.historial = historial;
    }

    static boolean esAdmin(Authentication a) {
        return a != null && a.getAuthorities().stream().anyMatch(g -> "ROLE_ADMIN".equals(g.getAuthority()));
    }

    @Operation(summary = "Padrón de clientes (paginado)", description = "Roles: ADMIN y VENTAS. `busqueda` por nombre, razón social, DNI, RUC o "
            + "teléfono; filtros `activo`, `mayorista` y `conComunicaciones` (autorización comercial vigente). Para VENTAS los datos de contacto y "
            + "documentos llegan enmascarados.")
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','VENTAS')")
    public ResponseEntity<PaginaResponse<ClienteResponse>> listar(
            @RequestParam(required = false) Boolean activo, @RequestParam(required = false) Boolean mayorista,
            @RequestParam(required = false) Boolean conComunicaciones, @RequestParam(required = false) String busqueda,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size, Authentication auth) {
        return ResponseEntity.ok(service.buscar(activo, mayorista, conComunicaciones, busqueda, Paginacion.de(page, size), esAdmin(auth)));
    }

    @Operation(summary = "Registrar un cliente (voluntario)", description = "Roles: ADMIN y VENTAS. Exige la autorización del comprador para el "
            + "registro (400 sin ella; la venta puede seguir como cliente varios). Nombre y teléfono obligatorios; correo, DNI y RUC opcionales. "
            + "La autorización de comunicaciones comerciales es aparte y opcional. 409 si el DNI/RUC ya es de otra ficha o de un proveedor, o si "
            + "el teléfono ya figura en otras fichas y no se confirmó que es otra persona. Tipo de cliente: `mayorista` = true solo ADMIN y con "
            + "`motivoMayorista`. `canalHabitual` = PUBLICO (por defecto) o MAYORISTA (solo mayoristas); PROVEEDOR se rechaza.")
    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','VENTAS')")
    public ResponseEntity<ClienteResponse> registrar(@RequestBody @Valid ClienteRequest dto, Authentication auth) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.registrar(dto, esAdmin(auth)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','VENTAS')")
    public ResponseEntity<ClienteResponse> obtener(@PathVariable UUID id, Authentication auth) {
        return ResponseEntity.ok(service.obtener(id, esAdmin(auth)));
    }

    @Operation(summary = "Editar datos del cliente", description = "Rol: ADMIN. Mismas reglas de documentos que al registrar. `canalHabitual` "
            + "opcional (null = no cambia).")
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ClienteResponse> actualizar(@PathVariable UUID id, @RequestBody @Valid ClienteUpdateRequest dto) {
        return ResponseEntity.ok(service.actualizar(id, dto));
    }

    @Operation(summary = "Agregar un teléfono", description = "Roles: ADMIN y VENTAS (por ejemplo, el comprador cambió de número).")
    @PostMapping("/{id}/telefonos")
    @PreAuthorize("hasAnyRole('ADMIN','VENTAS')")
    public ResponseEntity<ClienteResponse> agregarTelefono(@PathVariable UUID id, @RequestBody @Valid TelefonoRequest dto, Authentication auth) {
        return ResponseEntity.ok(service.agregarTelefono(id, dto.telefono(), esAdmin(auth)));
    }

    @Operation(summary = "Quitar un teléfono", description = "Rol: ADMIN. La ficha conserva al menos uno.")
    @DeleteMapping("/{id}/telefonos")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ClienteResponse> quitarTelefono(@PathVariable UUID id, @RequestParam String telefono) {
        return ResponseEntity.ok(service.quitarTelefono(id, telefono));
    }

    @Operation(summary = "Habilitar o quitar la condición de mayorista", description = "Rol: ADMIN, con motivo.")
    @PutMapping("/{id}/mayorista")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ClienteResponse> mayorista(@PathVariable UUID id, @RequestBody @Valid MayoristaRequest dto) {
        return ResponseEntity.ok(service.cambiarMayorista(id, dto.mayorista(), dto.motivo()));
    }

    @Operation(summary = "Habilitar o quitar a un cliente para un tipo de precio", description = "Rol: ADMIN, con motivo. Solo para tipos con regla CLIENTE_HABILITADO. Quitarla no cambia el tipo habitual guardado.")
    @PutMapping("/{id}/tipos-precio/{codigo}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ClienteResponse> habilitarTipo(@PathVariable UUID id, @PathVariable String codigo, @RequestBody @Valid com.planta.acopio.dto.ClienteDtos.HabilitacionTipoRequest dto) {
        return ResponseEntity.ok(service.cambiarHabilitacion(id, codigo, dto.habilitado(), dto.motivo()));
    }

    @PutMapping("/{id}/desactivar")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ClienteResponse> desactivar(@PathVariable UUID id) {
        return ResponseEntity.ok(service.cambiarActivo(id, false));
    }

    @PutMapping("/{id}/reactivar")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ClienteResponse> reactivar(@PathVariable UUID id) {
        return ResponseEntity.ok(service.cambiarActivo(id, true));
    }

    @Operation(summary = "Otorgar o revocar la autorización de comunicaciones comerciales", description = "Roles: ADMIN y VENTAS. Independiente "
            + "del registro. Otorgar exige medios y la versión del aviso; revocar rige desde ese momento y no necesita justificación.")
    @PostMapping("/{id}/comunicaciones")
    @PreAuthorize("hasAnyRole('ADMIN','VENTAS')")
    public ResponseEntity<ClienteResponse> comunicaciones(@PathVariable UUID id, @RequestBody @Valid ComercialRequest dto, Authentication auth) {
        return ResponseEntity.ok(service.registrarComercial(id, dto, esAdmin(auth)));
    }

    @Operation(summary = "Evidencia de autorizaciones", description = "Roles: ADMIN y VENTAS. Registro y comunicaciones, la más reciente primero.")
    @GetMapping("/{id}/consentimientos")
    @PreAuthorize("hasAnyRole('ADMIN','VENTAS')")
    public ResponseEntity<List<ConsentimientoResponse>> consentimientos(@PathVariable UUID id) {
        return ResponseEntity.ok(service.consentimientos(id));
    }

    @Operation(summary = "Historial: indicadores y reservas", description = "Roles: ADMIN y VENTAS. Solo ventas asociadas a esta ficha; el año es el "
            + "calendario en curso (America/Lima).")
    @GetMapping("/{id}/historial")
    @PreAuthorize("hasAnyRole('ADMIN','VENTAS')")
    public ResponseEntity<HistorialClienteResponse> historial(@PathVariable UUID id) {
        service.obtener(id, false);
        return ResponseEntity.ok(historial.historial(TipoComprador.CLIENTE, id));
    }

    @GetMapping("/{id}/compras")
    @PreAuthorize("hasAnyRole('ADMIN','VENTAS')")
    public ResponseEntity<PaginaResponse<CompraResponse>> compras(@PathVariable UUID id,
                                                                  @Parameter(description = "Página, desde 0") @RequestParam(defaultValue = "0") int page,
                                                                  @RequestParam(defaultValue = "20") int size) {
        service.obtener(id, false);
        return ResponseEntity.ok(historial.compras(TipoComprador.CLIENTE, id, Paginacion.de(page, size)));
    }

    @Operation(summary = "Fusionar una ficha duplicada en otra", description = "Rol: ADMIN, con motivo. Manual (nunca automática): las ventas y "
            + "reservas de la ficha origen pasan al destino, sus teléfonos se agregan, sus documentos se copian si el destino no los tiene; la origen "
            + "queda inactiva. 409 si los DNI o RUC no coinciden. Las autorizaciones no se transfieren.")
    @PostMapping("/{id}/fusionar")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<FusionResponse> fusionar(@PathVariable UUID id, @RequestBody @Valid FusionRequest dto) {
        return ResponseEntity.ok(service.fusionar(id, dto.destinoId(), dto.motivo()));
    }

    @GetMapping("/{id}/fusiones")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<FusionResponse>> fusiones(@PathVariable UUID id) {
        return ResponseEntity.ok(service.fusiones(id));
    }
}
