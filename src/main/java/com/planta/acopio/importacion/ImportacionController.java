package com.planta.acopio.importacion;

import com.planta.acopio.excel.RespuestaExcel;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

/**
 * Importacion de datos maestros desde Excel (proveedores, insumos), en dos pasos: vista previa y confirmacion. Solo ADMIN,
 * validado aqui y no solo en el panel. Nunca importa movimientos operativos.
 */
@Tag(name = "Importación desde Excel", description = "Plantilla oficial, vista previa, confirmación y reporte de errores. Solo ADMIN.")
@RestController
@RequestMapping("/api/admin/importaciones")
@PreAuthorize("hasRole('ADMIN')")
public class ImportacionController {

    private final ImportacionService service;

    public ImportacionController(ImportacionService service) {
        this.service = service;
    }

    @Operation(summary = "Descargar la plantilla oficial", description = "Tipos disponibles: proveedores, insumos. Trae instrucciones, encabezados, ejemplos y listas desplegables.")
    @GetMapping("/{tipo}/plantilla")
    public ResponseEntity<Resource> plantilla(@Parameter(description = "proveedores o insumos") @PathVariable String tipo) {
        return RespuestaExcel.de(service.plantilla(tipo));
    }

    @Operation(summary = "Vista previa de una importación",
            description = "Valida el archivo y devuelve las filas válidas, inválidas y duplicadas. NO crea ningún dato: hay que confirmar con POST /{id}/confirmar.")
    @PostMapping(value = "/{tipo}/previsualizar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<VistaPreviaImportacionResponse> previsualizar(@PathVariable String tipo,
                                                                         @RequestPart("archivo") MultipartFile archivo) {
        return ResponseEntity.ok(service.previsualizar(tipo, archivo));
    }

    @Operation(summary = "Confirmar una importación",
            description = "Crea las filas válidas de la vista previa en una sola transacción (todo o nada). Repetir la confirmación devuelve el resultado guardado sin crear nada otra vez.")
    @PostMapping("/{id}/confirmar")
    public ResponseEntity<ResultadoImportacionResponse> confirmar(@PathVariable UUID id) {
        return ResponseEntity.ok(service.confirmar(id));
    }

    @Operation(summary = "Descargar el reporte de filas no importadas", description = "Número de fila, resultado, motivo y valores de las filas inválidas, duplicadas u omitidas.")
    @GetMapping("/{id}/reporte")
    public ResponseEntity<Resource> reporte(@PathVariable UUID id) {
        return RespuestaExcel.de(service.reporte(id));
    }

    @Operation(summary = "Cancelar una vista previa", description = "Descarta la vista previa. Una importación ya confirmada no se puede cancelar.")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> descartar(@PathVariable UUID id) {
        service.descartar(id);
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }
}
