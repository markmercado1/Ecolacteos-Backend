package com.planta.acopio.controller;

import com.planta.acopio.dto.CrearVentaCarritoRequest;
import com.planta.acopio.dto.PaginaResponse;
import com.planta.acopio.dto.PrecioQuesoResponse;
import com.planta.acopio.dto.PresentacionVentaResponse;
import com.planta.acopio.dto.ResumenVentasResponse;
import com.planta.acopio.dto.VentaRequest;
import com.planta.acopio.dto.VentaResponse;
import com.planta.acopio.dto.VentaResumenResponse;
import com.planta.acopio.service.BuscadorPresentacionesVentaService;
import com.planta.acopio.service.ComprobanteVentaPdfService;
import com.planta.acopio.service.PrecioQuesoService;
import com.planta.acopio.service.VentaService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Tag(name = "Ventas", description = "Venta de queso a mayoristas, otros proveedores o público. Rol requerido: VENTAS o ADMIN.")
@RestController
@RequestMapping("/api/ventas")
@PreAuthorize("hasAnyRole('VENTAS','ADMIN')")
public class VentaController {

    private final VentaService ventaService;
    private final PrecioQuesoService precioQuesoService;
    private final BuscadorPresentacionesVentaService buscadorPresentaciones;
    private final ComprobanteVentaPdfService comprobantePdf;

    public VentaController(VentaService ventaService, PrecioQuesoService precioQuesoService,
                           BuscadorPresentacionesVentaService buscadorPresentaciones, ComprobanteVentaPdfService comprobantePdf) {
        this.ventaService = ventaService;
        this.precioQuesoService = precioQuesoService;
        this.buscadorPresentaciones = buscadorPresentaciones;
        this.comprobantePdf = comprobantePdf;
    }

    @Operation(
            summary = "Buscar presentaciones para vender (autocompletado)",
            description = "Presentaciones activas cuyo producto, presentación o código coinciden con `q` (sin distinguir mayúsculas ni "
                    + "acentos); primero las que EMPIEZAN con el texto. Cada una trae el stock vendible y el precio vigente para el "
                    + "canal `tipoCliente` y la `fecha` de la venta. `disponible` = false (con `motivo` SIN_STOCK o SIN_PRECIO) la "
                    + "muestra pero no permite venderla. `limite` por defecto 10 (máx. 20). Sin texto devuelve una lista vacía. Es una "
                    + "ayuda para elegir: el servidor vuelve a resolver precio y stock al registrar la venta."
    )
    @ApiResponse(responseCode = "200", description = "Presentaciones encontradas",
            content = @Content(array = @io.swagger.v3.oas.annotations.media.ArraySchema(schema = @Schema(implementation = PresentacionVentaResponse.class))))
    @GetMapping("/presentaciones")
    public ResponseEntity<List<PresentacionVentaResponse>> buscarPresentaciones(
            @Parameter(description = "Texto a buscar") @RequestParam(required = false, defaultValue = "") String q,
            @Parameter(description = "Canal (código de tipo de precio) de la venta") @RequestParam String tipoCliente,
            @Parameter(description = "Fecha de la venta (para el precio vigente)") @RequestParam LocalDate fecha,
            @Parameter(description = "Máximo de resultados (10 por defecto, máx. 20)") @RequestParam(required = false) Integer limite) {
        return ResponseEntity.ok(buscadorPresentaciones.buscar(q, tipoCliente, fecha, limite));
    }

    @Operation(
            summary = "Registrar una venta de una sola línea (contrato móvil)",
            description = "Idempotente por `uuidCliente` (capturable offline, usado por el sync de la app "
                    + "móvil). `tipoCliente` acepta MAYORISTA, PROVEEDOR o PUBLICO. La presentación debe "
                    + "estar activa. Para una venta con varias líneas (carrito), ver `POST /api/ventas/carrito`."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Venta creada (o la existente, si el uuidCliente ya se había recibido con el mismo contenido)",
                    content = @Content(schema = @Schema(implementation = VentaResponse.class))),
            @ApiResponse(responseCode = "400", description = "Datos inválidos, o sin precio vigente configurado"),
            @ApiResponse(responseCode = "404", description = "No existe una presentación con ese presentacionId"),
            @ApiResponse(responseCode = "409", description = "Ya existe una venta con ese uuidCliente pero con datos distintos"),
            @ApiResponse(responseCode = "422", description = "La presentación está desactivada")
    })
    @PostMapping
    public ResponseEntity<VentaResponse> crear(@RequestBody @Valid VentaRequest dto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ventaService.registrarOIgnorarSiDuplicado(dto));
    }

    // Fase 4 (Venta multi-linea / carrito): endpoint nuevo, exclusivo de la web. El contrato movil de arriba
    // no cambia (ver VentaService).
    @Operation(
            summary = "Registrar una venta con una o más líneas (carrito)",
            description = "Idempotente por `uuidCliente`. Cada línea indica una presentación y su cantidad; "
                    + "el precio de cada línea se resuelve en el servidor, igual que en la venta de una sola línea."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Venta creada (o la existente, si el uuidCliente ya se había recibido con el mismo contenido)",
                    content = @Content(schema = @Schema(implementation = VentaResumenResponse.class))),
            @ApiResponse(responseCode = "400", description = "Datos inválidos, sin precio vigente configurado, o una presentación repetida en la misma venta"),
            @ApiResponse(responseCode = "404", description = "No existe una presentación con alguno de los presentacionId indicados"),
            @ApiResponse(responseCode = "409", description = "Ya existe una venta con ese uuidCliente pero con datos distintos"),
            @ApiResponse(responseCode = "422", description = "Alguna presentación está desactivada")
    })
    @PostMapping("/carrito")
    public ResponseEntity<VentaResumenResponse> crearCarrito(@RequestBody @Valid CrearVentaCarritoRequest dto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ventaService.registrarCarritoOIgnorarSiDuplicado(dto));
    }

    // Auditoria CRUD (prioridad ALTA): el modulo no tenia ningun listado --
    // VENTAS no podia revisar su propio historial desde el frontend.
    // Bloque 5 (confirmado): filtros opcionales combinables -- sin parametros
    // se comporta exactamente igual que antes (no rompe al frontend actual).
    @Operation(
            summary = "Listar ventas (paginado)",
            description = "`fecha` (exacta) o `desde`/`hasta` (rango inclusivo), `tipoCliente` y `busqueda` son opcionales y combinables. `busqueda` busca en el vendedor, el comprador, el número de comprobante "
                    + "o en el producto/presentación de cualquier línea, entre TODAS las ventas (contiene, sin distinguir "
                    + "mayúsculas ni acentos). `page` (default 0) y `size` (default 20, máx. 100) controlan la paginación real. "
                    + "Orden: fecha más reciente primero y, dentro del mismo día, la venta registrada más recientemente primero. "
                    + "Cada venta incluye todas sus líneas."
    )
    @ApiResponse(responseCode = "200", description = "Página de ventas encontradas", content = @Content(schema = @Schema(implementation = PaginaResponse.class)))
    @GetMapping
    public ResponseEntity<PaginaResponse<VentaResumenResponse>> listar(
            @Parameter(description = "Filtrar por fecha exacta (si se indica, manda sobre desde/hasta)") @RequestParam(required = false) LocalDate fecha,
            @Parameter(description = "Desde esta fecha, inclusive") @RequestParam(required = false) LocalDate desde,
            @Parameter(description = "Hasta esta fecha, inclusive") @RequestParam(required = false) LocalDate hasta,
            @Parameter(description = "Filtrar por tipo de cliente (MAYORISTA, PROVEEDOR, PUBLICO)") @RequestParam(required = false) String tipoCliente,
            @Parameter(description = "Vendedor, comprador, número de comprobante, producto o presentación (contiene, sin distinguir mayúsculas ni acentos)") @RequestParam(required = false) String busqueda,
            @Parameter(description = "Número de página, empieza en 0") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Tamaño de página (máx. 100)") @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100));
        return ResponseEntity.ok(PaginaResponse.de(ventaService.buscar(
                fecha != null ? fecha : desde, fecha != null ? fecha : hasta, tipoCliente, busqueda, pageable)));
    }

    @Operation(
            summary = "Totales de las ventas de un filtro",
            description = "Cuántas ventas cumplen el filtro y cuánto suman. Mismos filtros y misma búsqueda que `GET /api/ventas`, sobre TODAS "
                    + "las ventas del filtro (no solo la página que se ve). Sin filtros, todas las ventas."
    )
    @ApiResponse(responseCode = "200", description = "Cantidad y total", content = @Content(schema = @Schema(implementation = ResumenVentasResponse.class)))
    @GetMapping("/resumen")
    public ResponseEntity<ResumenVentasResponse> resumen(
            @Parameter(description = "Filtrar por fecha exacta (si se indica, manda sobre desde/hasta)") @RequestParam(required = false) LocalDate fecha,
            @Parameter(description = "Desde esta fecha, inclusive") @RequestParam(required = false) LocalDate desde,
            @Parameter(description = "Hasta esta fecha, inclusive") @RequestParam(required = false) LocalDate hasta,
            @Parameter(description = "Filtrar por tipo de cliente") @RequestParam(required = false) String tipoCliente,
            @Parameter(description = "Misma búsqueda que el listado") @RequestParam(required = false) String busqueda) {
        return ResponseEntity.ok(ventaService.resumen(fecha != null ? fecha : desde, fecha != null ? fecha : hasta, tipoCliente, busqueda));
    }

    @Operation(summary = "Obtener el detalle de una venta (con todas sus líneas)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Venta encontrada", content = @Content(schema = @Schema(implementation = VentaResumenResponse.class))),
            @ApiResponse(responseCode = "404", description = "No existe una venta con ese id")
    })
    @GetMapping("/{id}")
    public ResponseEntity<VentaResumenResponse> obtener(@Parameter(description = "Id de la venta") @PathVariable UUID id) {
        return ResponseEntity.ok(ventaService.obtener(id));
    }

    // Comprobante de la venta en PDF (nota de venta o boleta, interno: sin validez tributaria). El navegador no puede mandar el token en
    // un enlace directo: el panel lo descarga con la sesion (blob) y lo abre o lo imprime.
    @Operation(summary = "Comprobante de la venta en PDF",
            description = "Nota de venta o boleta de la venta, con su número, hora, vendedor, comprador, líneas con su código, "
                    + "IGV (si se calculó) e importe en letras. Comprobante interno: no tiene validez tributaria. Se muestra en línea (inline).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "PDF del comprobante", content = @Content(mediaType = MediaType.APPLICATION_PDF_VALUE)),
            @ApiResponse(responseCode = "404", description = "No existe una venta con ese id")
    })
    @GetMapping(value = "/{id}/comprobante", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> comprobante(@Parameter(description = "Id de la venta") @PathVariable UUID id) {
        ComprobanteVentaPdfService.ComprobantePdf pdf = comprobantePdf.generarPdf(id);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(pdf.nombreArchivo()).build().toString())
                .cacheControl(CacheControl.noStore())
                .body(pdf.contenido());
    }

    // Fase 3 (Inventario por presentacion): recibe presentacionId directamente (VentaRequest tambien --
    // ver VentaService). Ya no resuelve "la unica presentacion activa de un producto": el usuario elige la
    // presentacion en el formulario, mismo criterio exacto que usa la confirmacion (POST /api/ventas o
    // POST /api/ventas/carrito).
    //
    // `fecha` es obligatorio (antes se resolvia siempre contra "hoy", una inconsistencia real con la
    // confirmacion, que ya usaba la fecha de la venta): asi la previsualizacion y la confirmacion usan
    // exactamente el mismo criterio, incluso si la venta se registra con una fecha pasada.
    @Operation(
            summary = "Consultar el precio vigente de una presentación para una modalidad y fecha",
            description = "Precio configurado en /precios para esa presentación, vigente a `fecha`. Uso: "
                    + "previsualizar el precio de cada línea antes de confirmar una venta, con el mismo "
                    + "criterio (misma fecha) que la confirmación."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Precio vigente encontrado", content = @Content(schema = @Schema(implementation = PrecioQuesoResponse.class))),
            @ApiResponse(responseCode = "400", description = "Sin precio vigente a esa fecha (nunca configurado, o programado para una fecha futura)"),
            @ApiResponse(responseCode = "404", description = "No existe una presentación con ese id")
    })
    @GetMapping("/precio-vigente")
    public ResponseEntity<PrecioQuesoResponse> precioVigente(
            @Parameter(description = "Id de la presentación") @RequestParam UUID presentacionId,
            @Parameter(description = "Modalidad: MAYORISTA, PROVEEDOR o PUBLICO") @RequestParam String tipoCliente,
            @Parameter(description = "Fecha de la venta (no \"hoy\"): misma fecha que se usará al confirmar") @RequestParam LocalDate fecha) {
        return ResponseEntity.ok(precioQuesoService.resolverParaConsulta(presentacionId, tipoCliente, fecha));
    }
}
