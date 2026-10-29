package com.planta.acopio.controller;

import com.planta.acopio.excel.ColumnasExportacion;
import com.planta.acopio.excel.ExportacionExcelService;
import com.planta.acopio.excel.FuentePaginada;
import com.planta.acopio.excel.RespuestaExcel;
import com.planta.acopio.service.KardexService;
import com.planta.acopio.service.LoteInsumoConsultaService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

// Exportacion a Excel del kardex de un insumo y de la lista de lotes (V43 a V46). Mismo patron que el resto de /api/exportaciones:
// todas las filas del filtro vigente (no solo la pagina visible) y los mismos roles que la consulta.
@Tag(name = "Exportación a Excel", description = "Descarga de las tablas del panel en .xlsx: todas las filas del filtro y la búsqueda actuales.")
@RestController
@RequestMapping("/api/exportaciones")
public class ExportacionInsumosController {

    private final ExportacionExcelService excel;
    private final KardexService kardexService;
    private final LoteInsumoConsultaService loteConsultaService;

    public ExportacionInsumosController(ExportacionExcelService excel, KardexService kardexService, LoteInsumoConsultaService loteConsultaService) {
        this.excel = excel;
        this.kardexService = kardexService;
        this.loteConsultaService = loteConsultaService;
    }

    @Operation(summary = "Exportar el kardex de un insumo", description = "Rol requerido: PRODUCCION o ADMIN. Mismos filtros que GET /api/inventario/kardex/insumos/{id}. Incluye lote, proveedor, vencimiento y tipo y número del documento de ingreso.")
    @GetMapping("/kardex-insumo/{insumoId}")
    @PreAuthorize("hasAnyRole('PRODUCCION','ADMIN')")
    public ResponseEntity<Resource> kardexInsumo(
            @PathVariable UUID insumoId,
            @Parameter(description = "Desde (inclusive)") @RequestParam(required = false) LocalDate desde,
            @Parameter(description = "Hasta (inclusive)") @RequestParam(required = false) LocalDate hasta,
            @Parameter(description = "Solo los movimientos de este lote") @RequestParam(required = false) UUID loteId) {
        return RespuestaExcel.de(excel.exportar("kardex-insumo", "Kardex", ColumnasExportacion.KARDEX_INSUMO,
                FuentePaginada.deRespuesta(pagina -> kardexService.insumo(insumoId, desde, hasta, loteId, pagina).movimientos())));
    }

    @Operation(summary = "Exportar la lista de lotes de insumo", description = "Rol requerido: PRODUCCION o ADMIN. `insumoId` y `estado` (VIGENTE, POR_VENCER, VENCIDO, AGOTADO) opcionales; `busqueda` por cualquier columna.")
    @GetMapping("/lotes-insumo")
    @PreAuthorize("hasAnyRole('PRODUCCION','ADMIN')")
    public ResponseEntity<Resource> lotesInsumo(
            @RequestParam(required = false) UUID insumoId,
            @RequestParam(required = false) String estado,
            @Parameter(description = "Texto contenido en cualquier columna (sin distinguir mayúsculas ni acentos)") @RequestParam(required = false) String busqueda) {
        return RespuestaExcel.de(excel.exportarLista("lotes-insumo", "Lotes", ColumnasExportacion.LOTES_INSUMO,
                loteConsultaService.listarTodos(insumoId, estado), busqueda));
    }
}
