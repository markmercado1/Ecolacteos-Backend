package com.planta.acopio.controller;

import com.planta.acopio.excel.ColumnasExportacion;
import com.planta.acopio.excel.ExportacionExcelService;
import com.planta.acopio.excel.RespuestaExcel;
import com.planta.acopio.service.PagoService;
import com.planta.acopio.service.ProveedorService;
import com.planta.acopio.service.RegistroAcopioService;
import com.planta.acopio.service.VentaService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Exportacion a Excel de las tablas del panel. Cada endpoint tiene LOS MISMOS roles y LOS MISMOS filtros que el listado
 * de su pantalla (y reutiliza su servicio), pero devuelve todas las filas del filtro, no una pagina. El rol se valida
 * aqui en el backend; ocultar el boton en el panel es solo comodidad.
 */
@Tag(name = "Exportación a Excel", description = "Descarga de las tablas del panel en .xlsx: todas las filas del filtro y la búsqueda actuales.")
@RestController
@RequestMapping("/api/exportaciones")
public class ExportacionController {

    private final ExportacionExcelService excel;
    private final ProveedorService proveedorService;
    private final RegistroAcopioService registroAcopioService;
    private final PagoService pagoService;
    private final VentaService ventaService;

    public ExportacionController(ExportacionExcelService excel, ProveedorService proveedorService,
                                 RegistroAcopioService registroAcopioService, PagoService pagoService,
                                 VentaService ventaService) {
        this.excel = excel;
        this.proveedorService = proveedorService;
        this.registroAcopioService = registroAcopioService;
        this.pagoService = pagoService;
        this.ventaService = ventaService;
    }

    @Operation(summary = "Exportar proveedores", description = "Rol requerido: ADMIN (incluye DNI y teléfono). Mismos filtros que GET /api/proveedores.")
    @GetMapping("/proveedores")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Resource> proveedores(
            @Parameter(description = "Filtrar por zona actual") @RequestParam(required = false) UUID zonaId,
            @Parameter(description = "Filtrar por estado activo/inactivo") @RequestParam(required = false) Boolean activo,
            @Parameter(description = "Nombre o DNI (contiene, sin distinguir mayúsculas ni acentos)") @RequestParam(required = false) String busqueda) {
        return RespuestaExcel.de(excel.exportar("proveedores", "Proveedores", ColumnasExportacion.PROVEEDORES,
                pagina -> proveedorService.buscar(zonaId, activo, busqueda, pagina)));
    }

    @Operation(summary = "Exportar acopios", description = "Rol requerido: ACOPIADOR, ADMIN, CALIDAD o PRODUCCION. Mismos filtros que GET /api/registros-acopio/paginado.")
    @GetMapping("/acopios")
    @PreAuthorize("hasAnyRole('ACOPIADOR','ADMIN','CALIDAD','PRODUCCION')")
    public ResponseEntity<Resource> acopios(
            @Parameter(description = "Día calendario completo") @RequestParam(required = false) LocalDate fecha,
            @Parameter(description = "Zona del proveedor") @RequestParam(required = false) UUID zonaId,
            @Parameter(description = "true = solo registros sin análisis de calidad") @RequestParam(required = false) Boolean sinAnalisis,
            @Parameter(description = "Proveedor o placa") @RequestParam(required = false) String busqueda) {
        return RespuestaExcel.de(excel.exportar("acopios", "Acopios", ColumnasExportacion.ACOPIOS,
                pagina -> registroAcopioService.buscarPaginado(fecha, zonaId, sinAnalisis, busqueda, pagina)));
    }

    @Operation(summary = "Exportar pagos", description = "Rol requerido: ADMIN o RECEPCION. Misma búsqueda que GET /api/pagos.")
    @GetMapping("/pagos")
    @PreAuthorize("hasAnyRole('ADMIN','RECEPCION')")
    public ResponseEntity<Resource> pagos(
            @Parameter(description = "Nombre o DNI del proveedor") @RequestParam(required = false) String busqueda) {
        return RespuestaExcel.de(excel.exportar("pagos", "Pagos", ColumnasExportacion.PAGOS,
                pagina -> pagoService.listar(busqueda, pagina)));
    }

    @Operation(summary = "Exportar ventas", description = "Rol requerido: VENTAS o ADMIN. Mismos filtros que GET /api/ventas. Una fila por línea vendida.")
    @GetMapping("/ventas")
    @PreAuthorize("hasAnyRole('VENTAS','ADMIN')")
    public ResponseEntity<Resource> ventas(
            @Parameter(description = "Fecha exacta") @RequestParam(required = false) LocalDate fecha,
            @Parameter(description = "MAYORISTA, PROVEEDOR o PUBLICO") @RequestParam(required = false) String tipoCliente,
            @Parameter(description = "Vendedor, producto o presentación") @RequestParam(required = false) String busqueda) {
        return RespuestaExcel.de(excel.exportar("ventas", "Ventas", ColumnasExportacion.VENTAS, pagina -> {
            var ventas = ventaService.buscar(fecha, tipoCliente, busqueda, pagina);
            return new PageImpl<>(ColumnasExportacion.lineasDe(ventas.getContent()), pagina, ventas.getTotalElements());
        }));
    }
}
