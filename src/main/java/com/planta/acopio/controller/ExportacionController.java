package com.planta.acopio.controller;

import com.planta.acopio.dto.UnidadResponse;
import com.planta.acopio.entity.EstadoLote;
import com.planta.acopio.exception.ValidacionNegocioException;
import com.planta.acopio.excel.ColumnasExportacion;
import com.planta.acopio.excel.ExportacionExcelService;
import com.planta.acopio.excel.FuentePaginada;
import com.planta.acopio.excel.RespuestaExcel;
import com.planta.acopio.service.AnalisisCalidadService;
import com.planta.acopio.service.AnomaliaService;
import com.planta.acopio.service.ClienteService;
import com.planta.acopio.service.ConciliacionService;
import com.planta.acopio.service.InformeProduccionDiarioService;
import com.planta.acopio.service.InventarioInsumoService;
import com.planta.acopio.service.InventarioService;
import com.planta.acopio.service.JornadaService;
import com.planta.acopio.service.LoteProduccionService;
import com.planta.acopio.service.PagoService;
import com.planta.acopio.service.ProveedorService;
import com.planta.acopio.service.RegistroAcopioService;
import com.planta.acopio.service.ReservaService;
import com.planta.acopio.service.UnidadService;
import com.planta.acopio.service.VentaService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.PageImpl;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Exportacion a Excel de las tablas operativas del panel. Cada endpoint tiene LOS MISMOS roles y LOS MISMOS filtros que el
 * listado de su pantalla (y reutiliza su servicio), pero devuelve todas las filas del filtro, no una pagina. El rol se
 * valida aqui en el backend; ocultar el boton en el panel es solo comodidad. Los catalogos y la administracion estan en
 * {@link ExportacionAdminController}.
 */
@Tag(name = "Exportación a Excel", description = "Descarga de las tablas del panel en .xlsx: todas las filas del filtro y la búsqueda actuales.")
@RestController
@RequestMapping("/api/exportaciones")
public class ExportacionController {

    private static final ZoneId ZONA_PLANTA = ZoneId.of("America/Lima");

    private final ExportacionExcelService excel;
    private final ProveedorService proveedorService;
    private final RegistroAcopioService registroAcopioService;
    private final PagoService pagoService;
    private final VentaService ventaService;
    private final ReservaService reservaService;
    private final ConciliacionService conciliacionService;
    private final UnidadService unidadService;
    private final LoteProduccionService loteProduccionService;
    private final InformeProduccionDiarioService informeService;
    private final InventarioService inventarioService;
    private final InventarioInsumoService inventarioInsumoService;
    private final AnalisisCalidadService analisisCalidadService;
    private final AnomaliaService anomaliaService;
    private final JornadaService jornadaService;
    private final ClienteService clienteService;

    public ExportacionController(ExportacionExcelService excel, ProveedorService proveedorService,
                                 RegistroAcopioService registroAcopioService, PagoService pagoService,
                                 VentaService ventaService, ReservaService reservaService,
                                 ConciliacionService conciliacionService, UnidadService unidadService,
                                 LoteProduccionService loteProduccionService, InformeProduccionDiarioService informeService,
                                 InventarioService inventarioService, InventarioInsumoService inventarioInsumoService,
                                 AnalisisCalidadService analisisCalidadService, AnomaliaService anomaliaService,
                                 JornadaService jornadaService, ClienteService clienteService) {
        this.excel = excel;
        this.proveedorService = proveedorService;
        this.registroAcopioService = registroAcopioService;
        this.pagoService = pagoService;
        this.ventaService = ventaService;
        this.reservaService = reservaService;
        this.conciliacionService = conciliacionService;
        this.unidadService = unidadService;
        this.loteProduccionService = loteProduccionService;
        this.informeService = informeService;
        this.inventarioService = inventarioService;
        this.inventarioInsumoService = inventarioInsumoService;
        this.analisisCalidadService = analisisCalidadService;
        this.anomaliaService = anomaliaService;
        this.jornadaService = jornadaService;
        this.clienteService = clienteService;
    }

    // ------------------------------------------------------------------------------- fase 2 (prioritarias)

    @Operation(summary = "Exportar proveedores", description = "Rol requerido: ADMIN (incluye DNI y teléfono). Mismos filtros que GET /api/proveedores.")
    @GetMapping("/proveedores")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Resource> proveedores(
            @Parameter(description = "Filtrar por zona actual") @RequestParam(required = false) UUID zonaId,
            @Parameter(description = "Filtrar por estado activo/inactivo") @RequestParam(required = false) Boolean activo,
            @Parameter(description = "Nombre, DNI, RUC o razón social (contiene, sin distinguir mayúsculas ni acentos)") @RequestParam(required = false) String busqueda) {
        return RespuestaExcel.de(excel.exportar("proveedores", "Proveedores", ColumnasExportacion.PROVEEDORES,
                pagina -> proveedorService.buscar(zonaId, activo, busqueda, pagina)));
    }

    @Operation(summary = "Exportar clientes", description = "Rol requerido: ADMIN (datos completos). Mismos filtros y búsqueda que GET /api/clientes.")
    @GetMapping("/clientes")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Resource> clientes(
            @Parameter(description = "Activos (true) o desactivados (false)") @RequestParam(required = false) Boolean activo,
            @Parameter(description = "Solo mayoristas") @RequestParam(required = false) Boolean mayorista,
            @Parameter(description = "Solo con autorización de promociones vigente") @RequestParam(required = false) Boolean conComunicaciones,
            @Parameter(description = "Nombre, razón social, DNI, RUC o teléfono") @RequestParam(required = false) String busqueda) {
        return RespuestaExcel.de(excel.exportar("clientes", "Clientes", ColumnasExportacion.CLIENTES,
                FuentePaginada.deRespuesta(pagina -> clienteService.buscar(activo, mayorista, conComunicaciones, busqueda, pagina, true))));
    }

    @Operation(summary = "Exportar acopios", description = "Rol requerido: ACOPIADOR, ADMIN, CALIDAD o PRODUCCION. Mismos filtros que GET /api/registros-acopio/paginado. Una fila por entrega.")
    @GetMapping("/acopios")
    @PreAuthorize("hasAnyRole('ACOPIADOR','ADMIN','CALIDAD','PRODUCCION')")
    public ResponseEntity<Resource> acopios(
            @Parameter(description = "Día calendario completo") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fecha,
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
            @Parameter(description = "Nombre, DNI, RUC o razón social del proveedor") @RequestParam(required = false) String busqueda) {
        return RespuestaExcel.de(excel.exportar("pagos", "Pagos", ColumnasExportacion.PAGOS,
                pagina -> pagoService.listar(busqueda, pagina)));
    }

    @Operation(summary = "Exportar ventas", description = "Rol requerido: VENTAS o ADMIN. Mismos filtros que GET /api/ventas. Una fila por línea vendida.")
    @GetMapping("/ventas")
    @PreAuthorize("hasAnyRole('VENTAS','ADMIN')")
    public ResponseEntity<Resource> ventas(
            @Parameter(description = "Fecha exacta") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fecha,
            @Parameter(description = "MAYORISTA, PROVEEDOR o PUBLICO") @RequestParam(required = false) String tipoCliente,
            @Parameter(description = "Vendedor, producto o presentación") @RequestParam(required = false) String busqueda) {
        return RespuestaExcel.de(excel.exportar("ventas", "Ventas", ColumnasExportacion.VENTAS, pagina -> {
            var ventas = ventaService.buscar(fecha, tipoCliente, busqueda, pagina);
            return new PageImpl<>(ColumnasExportacion.lineasDe(ventas.getContent()), pagina, ventas.getTotalElements());
        }));
    }

    // -------------------------------------------------------------------------------------- fase 3

    @Operation(summary = "Exportar reservas", description = "Rol requerido: VENTAS o ADMIN. Mismos filtros que GET /api/reservas (incluye el teléfono del cliente, como el listado).")
    @GetMapping("/reservas")
    @PreAuthorize("hasAnyRole('VENTAS','ADMIN')")
    public ResponseEntity<Resource> reservas(
            @Parameter(description = "PENDIENTE, CONFIRMADA, RECHAZADA, CANCELADA, VENCIDA o RETIRADA") @RequestParam(required = false) String estado,
            @Parameter(description = "Fecha de retiro (yyyy-MM-dd)") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fechaRetiro,
            @Parameter(description = "Teléfono del cliente") @RequestParam(required = false) String telefono,
            @Parameter(description = "Código de seguimiento exacto") @RequestParam(required = false) String codigo,
            @Parameter(description = "Cliente, teléfono o código") @RequestParam(required = false) String busqueda) {
        return RespuestaExcel.de(excel.exportar("reservas", "Reservas", ColumnasExportacion.RESERVAS,
                FuentePaginada.deRespuesta(pagina -> reservaService.listar(estado, fechaRetiro, telefono, codigo, busqueda, pagina))));
    }

    @Operation(summary = "Exportar recepciones de planta", description = "Rol requerido: RECEPCION o ADMIN. Mismos filtros que GET /api/recepcion-planta.")
    @GetMapping("/recepciones")
    @PreAuthorize("hasAnyRole('RECEPCION','ADMIN')")
    public ResponseEntity<Resource> recepciones(
            @Parameter(description = "Filtrar por unidad") @RequestParam(required = false) UUID unidadId,
            @Parameter(description = "Fecha exacta") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fecha,
            @Parameter(description = "Placa o responsable") @RequestParam(required = false) String busqueda) {
        // La recepcion solo trae el id de la unidad; la placa que muestra la pantalla se resuelve aqui.
        Map<UUID, String> placas = unidadService.listar().stream()
                .collect(Collectors.toMap(UnidadResponse::id, UnidadResponse::placa, (a, b) -> a));
        return RespuestaExcel.de(excel.exportar("recepciones", "Recepciones", ColumnasExportacion.recepciones(placas),
                pagina -> conciliacionService.listar(unidadId, fecha, busqueda, pagina)));
    }

    @Operation(summary = "Exportar lotes de producción", description = "Rol requerido: PRODUCCION o ADMIN. Mismos filtros que GET /api/lotes-produccion.")
    @GetMapping("/lotes")
    @PreAuthorize("hasAnyRole('PRODUCCION','ADMIN')")
    public ResponseEntity<Resource> lotes(
            @Parameter(description = "Fecha exacta") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fecha,
            @Parameter(description = "Filtrar por producto") @RequestParam(required = false) UUID productoId,
            @Parameter(description = "PLANIFICADA, EN_PRODUCCION, FINALIZADA o CANCELADA") @RequestParam(required = false) EstadoLote estado,
            @Parameter(description = "Producto o responsable") @RequestParam(required = false) String busqueda) {
        return RespuestaExcel.de(excel.exportar("lotes-produccion", "Lotes", ColumnasExportacion.LOTES,
                pagina -> loteProduccionService.buscar(fecha, productoId, estado, busqueda, pagina)));
    }

    @Operation(summary = "Exportar informes de producción diaria", description = "Rol requerido: PRODUCCION o ADMIN. Mismos filtros que GET /api/informes-produccion.")
    @GetMapping("/informes-produccion")
    @PreAuthorize("hasAnyRole('PRODUCCION','ADMIN')")
    public ResponseEntity<Resource> informesProduccion(
            @Parameter(description = "Desde esta fecha (inclusive)") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
            @Parameter(description = "Hasta esta fecha (inclusive)") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta,
            @Parameter(description = "Responsable") @RequestParam(required = false) String busqueda) {
        return RespuestaExcel.de(excel.exportar("informes-produccion", "Producción diaria", ColumnasExportacion.INFORMES_PRODUCCION,
                pagina -> informeService.buscar(desde, hasta, busqueda, pagina)));
    }

    @Operation(summary = "Exportar el stock de producto terminado", description = "Rol requerido: PRODUCCION, VENTAS o ADMIN. Mismos filtros que GET /api/inventario/stock/presentaciones.")
    @GetMapping("/stock")
    @PreAuthorize("hasAnyRole('PRODUCCION','VENTAS','ADMIN')")
    public ResponseEntity<Resource> stock(
            @Parameter(description = "Filtrar por familia") @RequestParam(required = false) UUID familiaId,
            @Parameter(description = "Producto o presentación") @RequestParam(required = false) String busqueda) {
        return RespuestaExcel.de(excel.exportar("stock", "Stock", ColumnasExportacion.STOCK,
                FuentePaginada.deRespuesta(pagina -> inventarioService.listarStockPaginado(familiaId, busqueda, pagina))));
    }

    @Operation(summary = "Exportar el stock de insumos", description = "Rol requerido: PRODUCCION o ADMIN. Mismos insumos que GET /api/inventario/insumos.")
    @GetMapping("/inventario-insumos")
    @PreAuthorize("hasAnyRole('PRODUCCION','ADMIN')")
    public ResponseEntity<Resource> inventarioInsumos(
            @Parameter(description = "Nombre o unidad (contiene, sin distinguir mayúsculas ni acentos)") @RequestParam(required = false) String busqueda) {
        return RespuestaExcel.de(excel.exportarLista("inventario-insumos", "Insumos", ColumnasExportacion.INSUMOS,
                inventarioInsumoService.listarParaInventario(), busqueda));
    }

    @Operation(summary = "Exportar la bandeja de analizados", description = "Rol requerido: CALIDAD o ADMIN. Mismo contenido que GET /api/analisis-calidad/bandeja; sin fecha, la de hoy.")
    @GetMapping("/calidad-analizados")
    @PreAuthorize("hasAnyRole('CALIDAD','ADMIN')")
    public ResponseEntity<Resource> calidadAnalizados(
            @Parameter(description = "Fecha calendario de la entrega") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fecha,
            @Parameter(description = "Proveedor o resultado") @RequestParam(required = false) String busqueda) {
        LocalDate dia = fecha != null ? fecha : LocalDate.now(ZONA_PLANTA);
        return RespuestaExcel.de(excel.exportarLista("calidad-analizados", "Analizados", ColumnasExportacion.CALIDAD_ANALIZADOS,
                analisisCalidadService.listarBandeja(dia), busqueda));
    }

    @Operation(summary = "Exportar las alertas de anomalía de una zona", description = "Rol requerido: CALIDAD o ADMIN. Mismas alertas (últimos 30 días) que GET /api/innovacion/alertas.")
    @GetMapping("/alertas-anomalia")
    @PreAuthorize("hasAnyRole('CALIDAD','ADMIN')")
    public ResponseEntity<Resource> alertasAnomalia(
            @Parameter(description = "Zona") @RequestParam(required = false) UUID zonaId,
            @Parameter(description = "Proveedor, tipo o severidad") @RequestParam(required = false) String busqueda) {
        if (zonaId == null) {
            throw new ValidacionNegocioException("Elige una zona para exportar sus alertas.");
        }
        return RespuestaExcel.de(excel.exportarLista("alertas-anomalia", "Alertas", ColumnasExportacion.ALERTAS_ANOMALIA,
                anomaliaService.listarAlertasPorZona(zonaId, 30), busqueda));
    }

    @Operation(summary = "Exportar la bandeja de jornadas pendientes de recepción", description = "Rol requerido: RECEPCION o ADMIN. Mismo contenido que GET /api/jornadas.")
    @GetMapping("/jornadas")
    @PreAuthorize("hasAnyRole('RECEPCION','ADMIN')")
    public ResponseEntity<Resource> jornadas(
            @Parameter(description = "Acopiador o vehículo") @RequestParam(required = false) String busqueda) {
        return RespuestaExcel.de(excel.exportarLista("jornadas", "Jornadas", ColumnasExportacion.JORNADAS,
                jornadaService.listarPendientesRecepcion(), busqueda));
    }
}
