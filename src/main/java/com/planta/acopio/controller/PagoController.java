package com.planta.acopio.controller;

import com.planta.acopio.dto.AjusteCalidadEntregaRequest;
import com.planta.acopio.dto.AjusteCalidadEntregaResponse;
import com.planta.acopio.dto.AjustePagoResponse;
import com.planta.acopio.dto.AjustePrecioPagoRequest;
import com.planta.acopio.dto.AjustePrecioPagoResponse;
import com.planta.acopio.dto.GenerarPagoRequest;
import com.planta.acopio.dto.LineaPagoResponse;
import com.planta.acopio.dto.PaginaResponse;
import com.planta.acopio.dto.PagoResponse;
import com.planta.acopio.service.AjusteCalidadEntregaService;
import com.planta.acopio.service.AjustePagoService;
import com.planta.acopio.service.AjustePrecioPagoService;
import com.planta.acopio.service.BoletaPagoService;
import com.planta.acopio.service.LineaPagoService;
import com.planta.acopio.service.PagoService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

// Decision aprobada §18 punto 6: lectura ADMIN + RECEPCION, escritura solo ADMIN.
@Tag(name = "Pagos", description = "Liquidación semanal a proveedores. Lectura: ADMIN y RECEPCION. Escritura: solo ADMIN.")
@RestController
@RequestMapping("/api/pagos")
public class PagoController {

    private final PagoService pagoService;
    private final AjustePagoService ajustePagoService;
    private final AjustePrecioPagoService ajustePrecioPagoService;
    private final BoletaPagoService boletaPagoService;
    private final LineaPagoService lineaPagoService;
    private final AjusteCalidadEntregaService ajusteCalidadEntregaService;

    public PagoController(PagoService pagoService, AjustePagoService ajustePagoService,
                          AjustePrecioPagoService ajustePrecioPagoService, BoletaPagoService boletaPagoService,
                          LineaPagoService lineaPagoService, AjusteCalidadEntregaService ajusteCalidadEntregaService) {
        this.pagoService = pagoService;
        this.ajustePagoService = ajustePagoService;
        this.ajustePrecioPagoService = ajustePrecioPagoService;
        this.boletaPagoService = boletaPagoService;
        this.lineaPagoService = lineaPagoService;
        this.ajusteCalidadEntregaService = ajusteCalidadEntregaService;
    }

    @Operation(
            summary = "Generar un pago",
            description = "Rol requerido: ADMIN. Suma los litros del proveedor en el rango de fechas al precio "
                    + "por litro vigente, y queda vinculado exactamente a los `RegistroAcopio` que liquida "
                    + "(trazabilidad exacta, no solo un acumulado). Rechaza si ya existe un pago para ese "
                    + "proveedor con la misma `semanaInicio`, o si algún registro del rango ya fue liquidado "
                    + "en otro pago con un rango de fechas distinto (evita doble liquidación por solape)."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Pago generado", content = @Content(schema = @Schema(implementation = PagoResponse.class))),
            @ApiResponse(responseCode = "400", description = "Datos inválidos, o no hay registros de acopio del proveedor en el rango indicado"),
            @ApiResponse(responseCode = "404", description = "El proveedor no existe, o no hay un precio por litro vigente configurado"),
            @ApiResponse(responseCode = "409", description = "Ya existe un pago para ese proveedor y semanaInicio, o algún registro del rango ya fue liquidado en otro pago")
    })
    @PostMapping("/generar")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<PagoResponse> generar(@RequestBody @Valid GenerarPagoRequest dto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(pagoService.generar(dto));
    }

    // Auditoria de bajo riesgo (Fase 1): listado global -- antes solo se podia
    // ver el historial por proveedor, uno a la vez.
    @Operation(summary = "Listar todos los pagos (paginado)", description = "Rol requerido: ADMIN o RECEPCION. "
            + "Orden: semana más reciente primero y, dentro de la misma semana, el pago creado más recientemente primero. "
            + "`busqueda` (opcional) busca en el nombre, el DNI, el RUC o la razón social del proveedor entre TODOS los pagos (contiene, sin distinguir "
            + "mayúsculas ni acentos). page empieza en 0; size se acota a 1..100.")
    @ApiResponse(responseCode = "200", description = "Página de pagos", content = @Content(schema = @Schema(implementation = PaginaResponse.class)))
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','RECEPCION')")
    public ResponseEntity<PaginaResponse<PagoResponse>> listar(
            @Parameter(description = "Nombre, DNI, RUC o razón social del proveedor (contiene, sin distinguir mayúsculas ni acentos)") @RequestParam(required = false) String busqueda,
            @Parameter(description = "Número de página, empieza en 0") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Tamaño de página (máx. 100)") @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(PaginaResponse.de(pagoService.listar(busqueda, Paginacion.de(page, size))));
    }

    // Contrato de la app movil (R-04): lista completa, sin paginar. No cambiar
    // su forma; la web usa la variante /paginado de abajo.
    @Operation(summary = "Historial de pagos de un proveedor", description = "Rol requerido: ADMIN o RECEPCION. Orden descendente por semanaInicio.")
    @ApiResponse(responseCode = "200", description = "Pagos del proveedor", content = @Content(array = @ArraySchema(schema = @Schema(implementation = PagoResponse.class))))
    @GetMapping("/proveedor/{proveedorId}")
    @PreAuthorize("hasAnyRole('ADMIN','RECEPCION')")
    public ResponseEntity<List<PagoResponse>> historialPorProveedor(@Parameter(description = "Id del proveedor") @PathVariable UUID proveedorId) {
        return ResponseEntity.ok(pagoService.historialPorProveedor(proveedorId));
    }

    @Operation(summary = "Historial de pagos de un proveedor (paginado)", description = "Rol requerido: ADMIN o RECEPCION. "
            + "Orden descendente por semanaInicio (desempate por id). page empieza en 0; size se acota a 1..100.")
    @ApiResponse(responseCode = "200", description = "Página de pagos del proveedor", content = @Content(schema = @Schema(implementation = PaginaResponse.class)))
    @GetMapping("/proveedor/{proveedorId}/paginado")
    @PreAuthorize("hasAnyRole('ADMIN','RECEPCION')")
    public ResponseEntity<PaginaResponse<PagoResponse>> historialPorProveedorPaginado(
            @Parameter(description = "Id del proveedor") @PathVariable UUID proveedorId,
            @Parameter(description = "Número de página, empieza en 0") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Tamaño de página (máx. 100)") @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(PaginaResponse.de(pagoService.historialPorProveedor(proveedorId, Paginacion.de(page, size))));
    }

    // Auditoria CRUD (prioridad MEDIA): faltaba el detalle individual de un pago.
    @Operation(summary = "Obtener el detalle de un pago", description = "Rol requerido: ADMIN o RECEPCION. Incluye los ids exactos de los registros de acopio liquidados.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Pago encontrado", content = @Content(schema = @Schema(implementation = PagoResponse.class))),
            @ApiResponse(responseCode = "404", description = "No existe un pago con ese id")
    })
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','RECEPCION')")
    public ResponseEntity<PagoResponse> obtener(@Parameter(description = "Id del pago") @PathVariable UUID id) {
        return ResponseEntity.ok(pagoService.obtener(id));
    }

    // Auditoria CRUD (prioridad MEDIA): Pago.comprobanteGenerado (RF-PAG-02)
    // existia en la entidad pero ningun endpoint lo ponia en true.
    @Operation(summary = "Marcar comprobante como generado", description = "Rol requerido: ADMIN. RF-PAG-02: registra que el comprobante ya se imprimió o mostró al proveedor.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Pago actualizado", content = @Content(schema = @Schema(implementation = PagoResponse.class))),
            @ApiResponse(responseCode = "404", description = "No existe un pago con ese id")
    })
    @PatchMapping("/{id}/comprobante")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<PagoResponse> marcarComprobanteGenerado(@Parameter(description = "Id del pago") @PathVariable UUID id) {
        return ResponseEntity.ok(pagoService.marcarComprobanteGenerado(id));
    }

    // Fase 3, decision de negocio 1 (opcion C, confirmada): ajustes generados
    // por correcciones anexadas despues de la liquidacion -- Pago y
    // RegistroAcopio siguen inmutables, esto es un registro auditable aparte.
    @Operation(summary = "Ajustes de un pago", description = "Rol requerido: ADMIN o RECEPCION. Ajustes generados por correcciones anexadas a registros ya liquidados en este pago.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Ajustes del pago", content = @Content(array = @ArraySchema(schema = @Schema(implementation = AjustePagoResponse.class)))),
            @ApiResponse(responseCode = "404", description = "No existe un pago con ese id")
    })
    @GetMapping("/{id}/ajustes")
    @PreAuthorize("hasAnyRole('ADMIN','RECEPCION')")
    public ResponseEntity<List<AjustePagoResponse>> ajustesDePago(@Parameter(description = "Id del pago") @PathVariable UUID id) {
        return ResponseEntity.ok(ajustePagoService.listarPorPago(id));
    }

    // Fase B: detalle por entrega de un pago. Sin datos personales del proveedor (RNF-12): lo lee tambien RECEPCION.
    @Operation(summary = "Entregas liquidadas en un pago", description = "Rol requerido: ADMIN o RECEPCION. Por cada entrega del pago: litros "
            + "originales (recibidos, inmutables), litros liquidados (original + última corrección válida al generar el pago), litros "
            + "contabilizados (liquidados + ajustes de litros posteriores), litros efectivos actuales, precio base y precio aplicado hoy "
            + "(incluye los ajustes de calidad del pago).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Entregas del pago", content = @Content(array = @ArraySchema(schema = @Schema(implementation = LineaPagoResponse.class)))),
            @ApiResponse(responseCode = "404", description = "No existe un pago con ese id")
    })
    @GetMapping("/{id}/lineas")
    @PreAuthorize("hasAnyRole('ADMIN','RECEPCION')")
    public ResponseEntity<List<LineaPagoResponse>> lineasDePago(@Parameter(description = "Id del pago") @PathVariable UUID id) {
        return ResponseEntity.ok(lineaPagoService.listarPorPago(id));
    }

    // Fase C: precio de calidad de UNA entrega de un pago (a mano, con analisis y motivo). Solo ADMIN, como el ajuste de precio del pago.
    @Operation(summary = "Aplicar un precio de calidad a una entrega de un pago", description = "Rol requerido: ADMIN. Fija el precio por litro "
            + "de UNA entrega ya liquidada en este pago (p. ej. 100 L de calidad deficiente a 0.90 dentro de un pago de 600 L a 1.00). Exige el "
            + "análisis de calidad que lo sustenta (debe ser de esa misma entrega) y un motivo. Afecta solo a esa entrega y manda sobre el ajuste "
            + "de precio del pago completo. El total reconocido del pago suma litros × precio de cada entrega y se redondea una sola vez. El pago "
            + "original no se modifica: queda un registro con precio anterior, nuevo, análisis, motivo, usuario y fecha. No hay regla automática.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Precio de calidad registrado", content = @Content(schema = @Schema(implementation = AjusteCalidadEntregaResponse.class))),
            @ApiResponse(responseCode = "400", description = "Precio inválido o igual al actual, motivo vacío, o el análisis no corresponde a la entrega"),
            @ApiResponse(responseCode = "404", description = "No existe el pago, la entrega no está liquidada en ese pago, o no existe el análisis")
    })
    @PostMapping("/{id}/lineas/{registroAcopioId}/ajuste-calidad")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<AjusteCalidadEntregaResponse> ajusteDeCalidad(
            @Parameter(description = "Id del pago") @PathVariable UUID id,
            @Parameter(description = "Id de la entrega (registro de acopio) liquidada en ese pago") @PathVariable UUID registroAcopioId,
            @RequestBody @Valid AjusteCalidadEntregaRequest dto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ajusteCalidadEntregaService.crear(id, registroAcopioId, dto));
    }

    @Operation(summary = "Historial de precios de calidad de un pago", description = "Rol requerido: ADMIN, RECEPCION o CALIDAD. Del más reciente al más antiguo; "
            + "cada uno con su análisis de calidad, motivo, usuario y fecha. Sin datos personales del proveedor.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Precios de calidad del pago", content = @Content(array = @ArraySchema(schema = @Schema(implementation = AjusteCalidadEntregaResponse.class)))),
            @ApiResponse(responseCode = "404", description = "No existe un pago con ese id")
    })
    @GetMapping("/{id}/ajustes-calidad")
    @PreAuthorize("hasAnyRole('ADMIN','RECEPCION','CALIDAD')")
    public ResponseEntity<List<AjusteCalidadEntregaResponse>> ajustesDeCalidad(@Parameter(description = "Id del pago") @PathVariable UUID id) {
        return ResponseEntity.ok(ajusteCalidadEntregaService.listarPorPago(id));
    }

    // Ajuste del precio por litro de UN pago (V24). El pago original no se modifica; el cambio queda en el historial
    // con motivo, usuario y fecha. Afecta solo a este pago.
    @Operation(summary = "Ajustar el precio por litro de un pago", description = "Rol requerido: ADMIN. Cambia el precio por litro "
            + "SOLO de este pago (p. ej. leche de mala calidad o un reconocimiento extra); el total a pagar se recalcula. El pago "
            + "original no se modifica: queda un registro con precio anterior, precio nuevo, motivo (obligatorio), usuario y fecha. "
            + "No cambia el precio general de la leche ni otros pagos. Un nuevo ajuste reemplaza al anterior en el cálculo.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Ajuste registrado", content = @Content(schema = @Schema(implementation = AjustePrecioPagoResponse.class))),
            @ApiResponse(responseCode = "400", description = "Precio inválido (cero, negativo o igual al actual) o motivo vacío"),
            @ApiResponse(responseCode = "404", description = "No existe un pago con ese id")
    })
    @PostMapping("/{id}/ajuste-precio")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<AjustePrecioPagoResponse> ajustarPrecio(@Parameter(description = "Id del pago") @PathVariable UUID id,
                                                                    @RequestBody @Valid AjustePrecioPagoRequest dto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ajustePrecioPagoService.crear(id, dto));
    }

    @Operation(summary = "Historial de ajustes de precio de un pago", description = "Rol requerido: ADMIN o RECEPCION. Más reciente primero.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Ajustes de precio del pago", content = @Content(array = @ArraySchema(schema = @Schema(implementation = AjustePrecioPagoResponse.class)))),
            @ApiResponse(responseCode = "404", description = "No existe un pago con ese id")
    })
    @GetMapping("/{id}/ajustes-precio")
    @PreAuthorize("hasAnyRole('ADMIN','RECEPCION')")
    public ResponseEntity<List<AjustePrecioPagoResponse>> ajustesDePrecio(@Parameter(description = "Id del pago") @PathVariable UUID id) {
        return ResponseEntity.ok(ajustePrecioPagoService.listarPorPago(id));
    }

    // Boleta de pago a proveedor en PDF (comprobante interno, no fiscal). El navegador no puede mandar el token en un
    // enlace directo: el panel la descarga con la sesion (blob) y la abre en otra pestaña.
    @Operation(summary = "Boleta de pago en PDF", description = "Rol requerido: ADMIN o RECEPCION. Comprobante interno del pago "
            + "(proveedor, semana, litros, precio, ajustes de precio con su motivo y total a pagar). No es una boleta ni una "
            + "factura electrónica. Se muestra en línea (inline).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "PDF de la boleta", content = @Content(mediaType = MediaType.APPLICATION_PDF_VALUE)),
            @ApiResponse(responseCode = "404", description = "No existe un pago con ese id")
    })
    @GetMapping(value = "/{id}/boleta", produces = MediaType.APPLICATION_PDF_VALUE)
    @PreAuthorize("hasAnyRole('ADMIN','RECEPCION')")
    public ResponseEntity<byte[]> boleta(@Parameter(description = "Id del pago") @PathVariable UUID id) {
        byte[] pdf = boletaPagoService.generarPdf(id);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                        .filename("boleta-pago-" + BoletaPagoService.codigo(id) + ".pdf").build().toString())
                .cacheControl(CacheControl.noStore())
                .body(pdf);
    }

    @Operation(summary = "Ajustes pendientes de un proveedor", description = "Rol requerido: ADMIN. Ajustes todavía no liquidados, para saber qué queda por saldar.")
    @ApiResponse(responseCode = "200", description = "Ajustes pendientes", content = @Content(array = @ArraySchema(schema = @Schema(implementation = AjustePagoResponse.class))))
    @GetMapping("/ajustes/pendientes")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<AjustePagoResponse>> ajustesPendientes(@Parameter(description = "Id del proveedor") @RequestParam UUID proveedorId) {
        return ResponseEntity.ok(ajustePagoService.listarPendientesPorProveedor(proveedorId));
    }

    @Operation(summary = "Marcar un ajuste como liquidado", description = "Rol requerido: ADMIN. Liquidación manual: ADMIN decide cuándo y cómo se salda (no se pliega automáticamente en un pago nuevo).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Ajuste liquidado", content = @Content(schema = @Schema(implementation = AjustePagoResponse.class))),
            @ApiResponse(responseCode = "400", description = "El ajuste ya estaba liquidado"),
            @ApiResponse(responseCode = "404", description = "No existe un ajuste con ese id")
    })
    @PatchMapping("/ajustes/{id}/liquidar")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<AjustePagoResponse> liquidarAjuste(@Parameter(description = "Id del ajuste") @PathVariable UUID id) {
        return ResponseEntity.ok(ajustePagoService.liquidar(id));
    }
}
