package com.planta.acopio.controller;
import com.planta.acopio.dto.SalidaInventarioRequest;
import com.planta.acopio.dto.SalidaInventarioResponse;
import com.planta.acopio.service.SalidaInventarioService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/inventario")
@PreAuthorize("hasRole('ADMIN')")
public class SalidaInventarioController {
    private final SalidaInventarioService service;
    public SalidaInventarioController(SalidaInventarioService service) { this.service = service; }
    @PostMapping("/presentaciones/{id}/salidas")
    public SalidaInventarioResponse producto(@PathVariable UUID id, @Valid @RequestBody SalidaInventarioRequest dto) {
        return service.registrar(id, false, dto);
    }
    @PostMapping("/insumos/{id}/salidas")
    public SalidaInventarioResponse insumo(@PathVariable UUID id, @Valid @RequestBody SalidaInventarioRequest dto) {
        return service.registrar(id, true, dto);
    }
}
