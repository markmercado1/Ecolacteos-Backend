package com.planta.acopio.controller;

import com.planta.acopio.dto.*;
import com.planta.acopio.service.ConfiguracionSistemaService;
import com.planta.acopio.service.FamiliaService;
import com.planta.acopio.service.InsumoService;
import com.planta.acopio.service.MotivoObservacionService;
import com.planta.acopio.service.PrecioLitroService;
import com.planta.acopio.service.PrecioQuesoService;
import com.planta.acopio.service.PresentacionService;
import com.planta.acopio.service.ProductoService;
import com.planta.acopio.service.RolService;
import com.planta.acopio.service.UnidadService;
import com.planta.acopio.service.ZonaService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

// CORE_DESIGN.md §12: administracion de catalogos, solo rol ADMIN.
// Agrupados en un unico controller (zona, unidad, motivo-observacion,
// tipo-queso, configuracion) por ser CRUD simple sin flujo de negocio propio
// -- separar cada uno en su propio archivo no aporta valor en v1.
// Documentacion OpenAPI (esta tarea): un unico controller cubre 7 recursos
// distintos, asi que se etiqueta (@Operation(tags=...)) por recurso en vez de
// a nivel de clase, para que Swagger UI los agrupe como modulos separados.
@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasRole('ADMIN')")
public class AdminCatalogosController {

    private static final String ROL_ADMIN = "Rol requerido: ADMIN.";

    private final ZonaService zonaService;
    private final UnidadService unidadService;
    private final MotivoObservacionService motivoObservacionService;
    private final FamiliaService familiaService;
    private final ProductoService productoService;
    private final PresentacionService presentacionService;
    private final ConfiguracionSistemaService configuracionSistemaService;
    private final PrecioLitroService precioLitroService;
    private final PrecioQuesoService precioQuesoService;
    private final RolService rolService;
    private final InsumoService insumoService;

    public AdminCatalogosController(ZonaService zonaService, UnidadService unidadService,
                                     MotivoObservacionService motivoObservacionService,
                                     FamiliaService familiaService,
                                     ProductoService productoService,
                                     PresentacionService presentacionService,
                                     ConfiguracionSistemaService configuracionSistemaService,
                                     PrecioLitroService precioLitroService,
                                     PrecioQuesoService precioQuesoService,
                                     RolService rolService,
                                     InsumoService insumoService) {
        this.zonaService = zonaService;
        this.unidadService = unidadService;
        this.motivoObservacionService = motivoObservacionService;
        this.familiaService = familiaService;
        this.productoService = productoService;
        this.presentacionService = presentacionService;
        this.configuracionSistemaService = configuracionSistemaService;
        this.precioLitroService = precioLitroService;
        this.precioQuesoService = precioQuesoService;
        this.rolService = rolService;
        this.insumoService = insumoService;
    }

    // --- Zonas ---
    @Operation(summary = "Crear zona", description = ROL_ADMIN, tags = "Zonas")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Zona creada", content = @Content(schema = @Schema(implementation = ZonaResponse.class))),
            @ApiResponse(responseCode = "400", description = "Datos inválidos")
    })
    @PostMapping("/zonas")
    public ResponseEntity<ZonaResponse> crearZona(@RequestBody @Valid ZonaRequest dto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(zonaService.crear(dto));
    }

    @Operation(summary = "Listar zonas activas", description = ROL_ADMIN, tags = "Zonas")
    @ApiResponse(responseCode = "200", description = "Zonas activas", content = @Content(array = @ArraySchema(schema = @Schema(implementation = ZonaResponse.class))))
    @GetMapping("/zonas")
    public ResponseEntity<List<ZonaResponse>> listarZonas() {
        return ResponseEntity.ok(zonaService.listarActivas());
    }

    // Auditoria CRUD (prioridad MEDIA): ZonaService.obtener ya existia pero
    // no estaba expuesto en ningun endpoint (codigo muerto).
    @Operation(summary = "Obtener zona por id", description = ROL_ADMIN, tags = "Zonas")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Zona encontrada", content = @Content(schema = @Schema(implementation = ZonaResponse.class))),
            @ApiResponse(responseCode = "404", description = "No existe una zona con ese id")
    })
    @GetMapping("/zonas/{id}")
    public ResponseEntity<ZonaResponse> obtenerZona(@Parameter(description = "Id de la zona") @PathVariable UUID id) {
        return ResponseEntity.ok(zonaService.obtener(id));
    }

    // Auditoria CRUD (prioridad MEDIA): faltaba poder corregir el nombre de una zona.
    @Operation(summary = "Editar zona", description = ROL_ADMIN, tags = "Zonas")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Zona actualizada", content = @Content(schema = @Schema(implementation = ZonaResponse.class))),
            @ApiResponse(responseCode = "400", description = "Datos inválidos"),
            @ApiResponse(responseCode = "404", description = "No existe una zona con ese id")
    })
    @PutMapping("/zonas/{id}")
    public ResponseEntity<ZonaResponse> actualizarZona(@Parameter(description = "Id de la zona") @PathVariable UUID id,
                                                         @RequestBody @Valid ZonaRequest dto) {
        return ResponseEntity.ok(zonaService.actualizar(id, dto));
    }

    @Operation(summary = "Desactivar zona", description = ROL_ADMIN + " Baja lógica (activa=false).", tags = "Zonas")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Zona desactivada"),
            @ApiResponse(responseCode = "404", description = "No existe una zona con ese id")
    })
    @DeleteMapping("/zonas/{id}")
    public ResponseEntity<Void> desactivarZona(@Parameter(description = "Id de la zona") @PathVariable UUID id) {
        zonaService.desactivar(id);
        return ResponseEntity.noContent().build();
    }

    // Auditoria de bajo riesgo (Fase 3): la baja logica no tenia forma de revertirse.
    @Operation(summary = "Reactivar zona", description = ROL_ADMIN + " Revierte la baja lógica (activa=true).", tags = "Zonas")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Zona reactivada", content = @Content(schema = @Schema(implementation = ZonaResponse.class))),
            @ApiResponse(responseCode = "404", description = "No existe una zona con ese id")
    })
    @PatchMapping("/zonas/{id}/reactivar")
    public ResponseEntity<ZonaResponse> reactivarZona(@Parameter(description = "Id de la zona") @PathVariable UUID id) {
        return ResponseEntity.ok(zonaService.reactivar(id));
    }

    // --- Unidades ---
    @Operation(summary = "Crear unidad (camión)", description = ROL_ADMIN + " La placa debe ser única; responsableId es obligatorio.", tags = "Unidades")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Unidad creada", content = @Content(schema = @Schema(implementation = UnidadResponse.class))),
            @ApiResponse(responseCode = "400", description = "Datos inválidos"),
            @ApiResponse(responseCode = "404", description = "El responsableId o la zonaId indicados no existen"),
            @ApiResponse(responseCode = "409", description = "Ya existe una unidad con esa placa")
    })
    @PostMapping("/unidades")
    public ResponseEntity<UnidadResponse> crearUnidad(@RequestBody @Valid UnidadRequest dto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(unidadService.crear(dto));
    }

    @Operation(summary = "Listar unidades", description = ROL_ADMIN, tags = "Unidades")
    @ApiResponse(responseCode = "200", description = "Todas las unidades", content = @Content(array = @ArraySchema(schema = @Schema(implementation = UnidadResponse.class))))
    @GetMapping("/unidades")
    public ResponseEntity<List<UnidadResponse>> listarUnidades() {
        return ResponseEntity.ok(unidadService.listar());
    }

    // Auditoria CRUD (prioridad MEDIA): faltaba reasignar zona/responsable o
    // corregir la placa de una unidad ya creada. Sin DELETE: "unidad" no
    // tiene columna activo en schema.sql (requeriria migracion, fuera de alcance).
    @Operation(summary = "Editar unidad", description = ROL_ADMIN + " Sin baja lógica: la tabla `unidad` no tiene columna `activo` en el esquema actual.", tags = "Unidades")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Unidad actualizada", content = @Content(schema = @Schema(implementation = UnidadResponse.class))),
            @ApiResponse(responseCode = "400", description = "Datos inválidos"),
            @ApiResponse(responseCode = "404", description = "La unidad, el responsableId o la zonaId indicados no existen"),
            @ApiResponse(responseCode = "409", description = "Ya existe otra unidad con esa placa")
    })
    @PutMapping("/unidades/{id}")
    public ResponseEntity<UnidadResponse> actualizarUnidad(@Parameter(description = "Id de la unidad") @PathVariable UUID id,
                                                             @RequestBody @Valid UnidadRequest dto) {
        return ResponseEntity.ok(unidadService.actualizar(id, dto));
    }

    // --- Motivos de observacion ---
    @Operation(summary = "Crear motivo de observación", description = ROL_ADMIN, tags = "Motivos de Observación")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Motivo creado", content = @Content(schema = @Schema(implementation = MotivoObservacionResponse.class))),
            @ApiResponse(responseCode = "400", description = "Datos inválidos")
    })
    @PostMapping("/motivos-observacion")
    public ResponseEntity<MotivoObservacionResponse> crearMotivo(@RequestBody @Valid MotivoObservacionRequest dto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(motivoObservacionService.crear(dto));
    }

    @Operation(summary = "Listar motivos de observación activos", description = ROL_ADMIN, tags = "Motivos de Observación")
    @ApiResponse(responseCode = "200", description = "Motivos activos", content = @Content(array = @ArraySchema(schema = @Schema(implementation = MotivoObservacionResponse.class))))
    @GetMapping("/motivos-observacion")
    public ResponseEntity<List<MotivoObservacionResponse>> listarMotivos() {
        return ResponseEntity.ok(motivoObservacionService.listarActivos());
    }

    // Auditoria CRUD (prioridad MEDIA): faltaba corregir la descripcion de un motivo.
    @Operation(summary = "Editar motivo de observación", description = ROL_ADMIN, tags = "Motivos de Observación")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Motivo actualizado", content = @Content(schema = @Schema(implementation = MotivoObservacionResponse.class))),
            @ApiResponse(responseCode = "400", description = "Datos inválidos"),
            @ApiResponse(responseCode = "404", description = "No existe un motivo con ese id")
    })
    @PutMapping("/motivos-observacion/{id}")
    public ResponseEntity<MotivoObservacionResponse> actualizarMotivo(@Parameter(description = "Id del motivo") @PathVariable UUID id,
                                                                        @RequestBody @Valid MotivoObservacionRequest dto) {
        return ResponseEntity.ok(motivoObservacionService.actualizar(id, dto));
    }

    // Auditoria CRUD (prioridad MEDIA): el campo activo ya existia en la
    // entidad (igual que Zona/TipoQueso) pero nunca se exponia la baja logica.
    @Operation(summary = "Desactivar motivo de observación", description = ROL_ADMIN + " Baja lógica (activo=false).", tags = "Motivos de Observación")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Motivo desactivado"),
            @ApiResponse(responseCode = "404", description = "No existe un motivo con ese id")
    })
    @DeleteMapping("/motivos-observacion/{id}")
    public ResponseEntity<Void> desactivarMotivo(@Parameter(description = "Id del motivo") @PathVariable UUID id) {
        motivoObservacionService.desactivar(id);
        return ResponseEntity.noContent().build();
    }

    // Auditoria de bajo riesgo (Fase 3): la baja logica no tenia forma de revertirse.
    @Operation(summary = "Reactivar motivo de observación", description = ROL_ADMIN + " Revierte la baja lógica (activo=true).", tags = "Motivos de Observación")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Motivo reactivado", content = @Content(schema = @Schema(implementation = MotivoObservacionResponse.class))),
            @ApiResponse(responseCode = "404", description = "No existe un motivo con ese id")
    })
    @PatchMapping("/motivos-observacion/{id}/reactivar")
    public ResponseEntity<MotivoObservacionResponse> reactivarMotivo(@Parameter(description = "Id del motivo") @PathVariable UUID id) {
        return ResponseEntity.ok(motivoObservacionService.reactivar(id));
    }

    // --- Familias (Fase 1: Producto + Presentacion) ---
    // Catalogo administrable desde la web: el usuario autorizado crea familias (Queso, Yogurt, Leche,
    // Mantequilla, Dulce de leche...) sin tocar codigo ni SQL. Mismo patron CRUD que Zona.
    @Operation(summary = "Crear familia de producto", description = ROL_ADMIN, tags = "Familias")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Familia creada", content = @Content(schema = @Schema(implementation = FamiliaResponse.class))),
            @ApiResponse(responseCode = "400", description = "Datos inválidos")
    })
    @PostMapping("/familias")
    public ResponseEntity<FamiliaResponse> crearFamilia(@RequestBody @Valid FamiliaRequest dto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(familiaService.crear(dto));
    }

    @Operation(summary = "Listar familias activas", description = ROL_ADMIN, tags = "Familias")
    @ApiResponse(responseCode = "200", description = "Familias activas", content = @Content(array = @ArraySchema(schema = @Schema(implementation = FamiliaResponse.class))))
    @GetMapping("/familias")
    public ResponseEntity<List<FamiliaResponse>> listarFamilias() {
        return ResponseEntity.ok(familiaService.listarActivas());
    }

    @Operation(summary = "Editar familia de producto", description = ROL_ADMIN, tags = "Familias")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Familia actualizada", content = @Content(schema = @Schema(implementation = FamiliaResponse.class))),
            @ApiResponse(responseCode = "400", description = "Datos inválidos"),
            @ApiResponse(responseCode = "404", description = "No existe una familia con ese id")
    })
    @PutMapping("/familias/{id}")
    public ResponseEntity<FamiliaResponse> actualizarFamilia(@Parameter(description = "Id de la familia") @PathVariable UUID id,
                                                               @RequestBody @Valid FamiliaRequest dto) {
        return ResponseEntity.ok(familiaService.actualizar(id, dto));
    }

    @Operation(summary = "Desactivar familia de producto", description = ROL_ADMIN + " Baja lógica (activo=false).", tags = "Familias")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Familia desactivada"),
            @ApiResponse(responseCode = "404", description = "No existe una familia con ese id")
    })
    @DeleteMapping("/familias/{id}")
    public ResponseEntity<Void> desactivarFamilia(@Parameter(description = "Id de la familia") @PathVariable UUID id) {
        familiaService.desactivar(id);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Reactivar familia de producto", description = ROL_ADMIN + " Revierte la baja lógica (activo=true).", tags = "Familias")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Familia reactivada", content = @Content(schema = @Schema(implementation = FamiliaResponse.class))),
            @ApiResponse(responseCode = "404", description = "No existe una familia con ese id")
    })
    @PatchMapping("/familias/{id}/reactivar")
    public ResponseEntity<FamiliaResponse> reactivarFamilia(@Parameter(description = "Id de la familia") @PathVariable UUID id) {
        return ResponseEntity.ok(familiaService.reactivar(id));
    }

    // --- Productos (Fase 1: Producto + Presentacion) ---
    // Generaliza "Tipos de queso": mismo recurso, ahora con familia y presentaciones propias. rendimiento
    // esperado/ciclo de capital dejan de ser obligatorios -- son atributos de produccion de queso
    // especificamente, no comerciales (ver ProductoService/Producto).
    @Operation(summary = "Crear producto", description = ROL_ADMIN, tags = "Productos")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Producto creado", content = @Content(schema = @Schema(implementation = ProductoResponse.class))),
            @ApiResponse(responseCode = "400", description = "Datos inválidos (nombre vacío, familiaId nulo)"),
            @ApiResponse(responseCode = "404", description = "No existe una familia con ese familiaId"),
            @ApiResponse(responseCode = "409", description = "Ya existe un producto con ese nombre")
    })
    @PostMapping("/productos")
    public ResponseEntity<ProductoResponse> crearProducto(@RequestBody @Valid ProductoRequest dto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(productoService.crear(dto));
    }

    @Operation(summary = "Listar productos activos", description = ROL_ADMIN + " Incluye las presentaciones de cada producto.", tags = "Productos")
    @ApiResponse(responseCode = "200", description = "Productos activos", content = @Content(array = @ArraySchema(schema = @Schema(implementation = ProductoResponse.class))))
    @GetMapping("/productos")
    public ResponseEntity<List<ProductoResponse>> listarProductos() {
        return ResponseEntity.ok(productoService.listarActivos());
    }

    @Operation(summary = "Editar producto", description = ROL_ADMIN, tags = "Productos")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Producto actualizado", content = @Content(schema = @Schema(implementation = ProductoResponse.class))),
            @ApiResponse(responseCode = "400", description = "Datos inválidos"),
            @ApiResponse(responseCode = "404", description = "No existe un producto o una familia con esos ids")
    })
    @PutMapping("/productos/{id}")
    public ResponseEntity<ProductoResponse> actualizarProducto(@Parameter(description = "Id del producto") @PathVariable UUID id,
                                                                 @RequestBody @Valid ProductoRequest dto) {
        return ResponseEntity.ok(productoService.actualizar(id, dto));
    }

    @Operation(summary = "Desactivar producto", description = ROL_ADMIN + " Baja lógica (activo=false).", tags = "Productos")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Producto desactivado"),
            @ApiResponse(responseCode = "404", description = "No existe un producto con ese id")
    })
    @DeleteMapping("/productos/{id}")
    public ResponseEntity<Void> desactivarProducto(@Parameter(description = "Id del producto") @PathVariable UUID id) {
        productoService.desactivar(id);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Reactivar producto", description = ROL_ADMIN + " Revierte la baja lógica (activo=true).", tags = "Productos")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Producto reactivado", content = @Content(schema = @Schema(implementation = ProductoResponse.class))),
            @ApiResponse(responseCode = "404", description = "No existe un producto con ese id")
    })
    @PatchMapping("/productos/{id}/reactivar")
    public ResponseEntity<ProductoResponse> reactivarProducto(@Parameter(description = "Id del producto") @PathVariable UUID id) {
        return ResponseEntity.ok(productoService.reactivar(id));
    }

    // --- Fotos del catalogo (producto y presentacion) ---
    // Una foto opcional por producto y otra por presentacion. Se sirven SIN autenticacion por
    // GET /api/catalogo/fotos/{nombre} (ver CatalogoFotosController): son las que vera el publico.
    @Operation(summary = "Subir o reemplazar la foto de un producto", description = ROL_ADMIN
            + " multipart/form-data, campo `file`. JPEG, PNG o WebP, máx. 5 MB. Reemplaza la anterior (se borra del disco).",
            tags = "Productos")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Foto guardada", content = @Content(schema = @Schema(implementation = ProductoResponse.class))),
            @ApiResponse(responseCode = "400", description = "Archivo vacío, formato no permitido, demasiado grande o dañado"),
            @ApiResponse(responseCode = "404", description = "No existe un producto con ese id")
    })
    @PutMapping(value = "/productos/{id}/foto", consumes = "multipart/form-data")
    public ResponseEntity<ProductoResponse> subirFotoProducto(@Parameter(description = "Id del producto") @PathVariable UUID id,
                                                                @RequestParam("file") MultipartFile file) {
        return ResponseEntity.ok(productoService.reemplazarFoto(id, file));
    }

    @Operation(summary = "Quitar la foto de un producto", description = ROL_ADMIN, tags = "Productos")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Foto quitada", content = @Content(schema = @Schema(implementation = ProductoResponse.class))),
            @ApiResponse(responseCode = "404", description = "No existe un producto con ese id")
    })
    @DeleteMapping("/productos/{id}/foto")
    public ResponseEntity<ProductoResponse> quitarFotoProducto(@Parameter(description = "Id del producto") @PathVariable UUID id) {
        return ResponseEntity.ok(productoService.quitarFoto(id));
    }

    @Operation(summary = "Subir o reemplazar la foto de una presentación", description = ROL_ADMIN
            + " multipart/form-data, campo `file`. JPEG, PNG o WebP, máx. 5 MB. Si la presentación no tiene foto propia, el catálogo usa la del producto.",
            tags = "Productos")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Foto guardada", content = @Content(schema = @Schema(implementation = PresentacionResponse.class))),
            @ApiResponse(responseCode = "400", description = "Archivo vacío, formato no permitido, demasiado grande o dañado"),
            @ApiResponse(responseCode = "404", description = "No existe esa presentación para este producto")
    })
    @PutMapping(value = "/productos/{productoId}/presentaciones/{presentacionId}/foto", consumes = "multipart/form-data")
    public ResponseEntity<PresentacionResponse> subirFotoPresentacion(
            @Parameter(description = "Id del producto") @PathVariable UUID productoId,
            @Parameter(description = "Id de la presentación") @PathVariable UUID presentacionId,
            @RequestParam("file") MultipartFile file) {
        return ResponseEntity.ok(presentacionService.reemplazarFoto(productoId, presentacionId, file));
    }

    @Operation(summary = "Quitar la foto de una presentación", description = ROL_ADMIN, tags = "Productos")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Foto quitada", content = @Content(schema = @Schema(implementation = PresentacionResponse.class))),
            @ApiResponse(responseCode = "404", description = "No existe esa presentación para este producto")
    })
    @DeleteMapping("/productos/{productoId}/presentaciones/{presentacionId}/foto")
    public ResponseEntity<PresentacionResponse> quitarFotoPresentacion(
            @Parameter(description = "Id del producto") @PathVariable UUID productoId,
            @Parameter(description = "Id de la presentación") @PathVariable UUID presentacionId) {
        return ResponseEntity.ok(presentacionService.quitarFoto(productoId, presentacionId));
    }

    // --- Presentaciones (dentro de la ficha del producto, no es un modulo aparte) ---
    // Como se cuenta/almacena/vende un producto (ej. "Botella 1 L", "Pieza"). Crear una presentacion NO
    // genera stock ni precio (operaciones distintas, ver Precios/Inventario en una fase posterior).
    @Operation(summary = "Agregar una presentación a un producto", description = ROL_ADMIN, tags = "Productos")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Presentación creada", content = @Content(schema = @Schema(implementation = PresentacionResponse.class))),
            @ApiResponse(responseCode = "400", description = "Datos inválidos"),
            @ApiResponse(responseCode = "404", description = "No existe un producto con ese id"),
            @ApiResponse(responseCode = "409", description = "Ya existe una presentación con ese nombre para este producto")
    })
    @PostMapping("/productos/{productoId}/presentaciones")
    public ResponseEntity<PresentacionResponse> crearPresentacion(@Parameter(description = "Id del producto") @PathVariable UUID productoId,
                                                                    @RequestBody @Valid PresentacionRequest dto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(presentacionService.crear(productoId, dto));
    }

    @Operation(summary = "Editar una presentación", description = ROL_ADMIN, tags = "Productos")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Presentación actualizada", content = @Content(schema = @Schema(implementation = PresentacionResponse.class))),
            @ApiResponse(responseCode = "400", description = "Datos inválidos"),
            @ApiResponse(responseCode = "404", description = "No existe esa presentación para este producto")
    })
    @PutMapping("/productos/{productoId}/presentaciones/{presentacionId}")
    public ResponseEntity<PresentacionResponse> actualizarPresentacion(
            @Parameter(description = "Id del producto") @PathVariable UUID productoId,
            @Parameter(description = "Id de la presentación") @PathVariable UUID presentacionId,
            @RequestBody @Valid PresentacionRequest dto) {
        return ResponseEntity.ok(presentacionService.actualizar(productoId, presentacionId, dto));
    }

    @Operation(summary = "Desactivar una presentación", description = ROL_ADMIN + " Baja lógica (activo=false).", tags = "Productos")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Presentación desactivada"),
            @ApiResponse(responseCode = "404", description = "No existe esa presentación para este producto")
    })
    @DeleteMapping("/productos/{productoId}/presentaciones/{presentacionId}")
    public ResponseEntity<Void> desactivarPresentacion(
            @Parameter(description = "Id del producto") @PathVariable UUID productoId,
            @Parameter(description = "Id de la presentación") @PathVariable UUID presentacionId) {
        presentacionService.desactivar(productoId, presentacionId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Reactivar una presentación", description = ROL_ADMIN + " Revierte la baja lógica (activo=true).", tags = "Productos")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Presentación reactivada", content = @Content(schema = @Schema(implementation = PresentacionResponse.class))),
            @ApiResponse(responseCode = "404", description = "No existe esa presentación para este producto")
    })
    @PatchMapping("/productos/{productoId}/presentaciones/{presentacionId}/reactivar")
    public ResponseEntity<PresentacionResponse> reactivarPresentacion(
            @Parameter(description = "Id del producto") @PathVariable UUID productoId,
            @Parameter(description = "Id de la presentación") @PathVariable UUID presentacionId) {
        return ResponseEntity.ok(presentacionService.reactivar(productoId, presentacionId));
    }

    // --- Insumos (cuajo, sal, etc.) ---
    @Operation(summary = "Crear insumo", description = ROL_ADMIN, tags = "Insumos")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Insumo creado", content = @Content(schema = @Schema(implementation = InsumoResponse.class))),
            @ApiResponse(responseCode = "400", description = "Datos inválidos"),
            @ApiResponse(responseCode = "409", description = "Ya existe un insumo con ese nombre")
    })
    @PostMapping("/insumos")
    public ResponseEntity<InsumoResponse> crearInsumo(@RequestBody @Valid InsumoRequest dto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(insumoService.crear(dto));
    }

    @Operation(summary = "Listar insumos activos", description = ROL_ADMIN, tags = "Insumos")
    @ApiResponse(responseCode = "200", description = "Insumos activos", content = @Content(array = @ArraySchema(schema = @Schema(implementation = InsumoResponse.class))))
    @GetMapping("/insumos")
    public ResponseEntity<List<InsumoResponse>> listarInsumos() {
        return ResponseEntity.ok(insumoService.listarActivos());
    }

    @Operation(summary = "Obtener insumo por id", description = ROL_ADMIN, tags = "Insumos")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Insumo encontrado", content = @Content(schema = @Schema(implementation = InsumoResponse.class))),
            @ApiResponse(responseCode = "404", description = "No existe un insumo con ese id")
    })
    @GetMapping("/insumos/{id}")
    public ResponseEntity<InsumoResponse> obtenerInsumo(@Parameter(description = "Id del insumo") @PathVariable UUID id) {
        return ResponseEntity.ok(insumoService.obtener(id));
    }

    @Operation(summary = "Editar insumo", description = ROL_ADMIN, tags = "Insumos")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Insumo actualizado", content = @Content(schema = @Schema(implementation = InsumoResponse.class))),
            @ApiResponse(responseCode = "400", description = "Datos inválidos"),
            @ApiResponse(responseCode = "404", description = "No existe un insumo con ese id")
    })
    @PutMapping("/insumos/{id}")
    public ResponseEntity<InsumoResponse> actualizarInsumo(@Parameter(description = "Id del insumo") @PathVariable UUID id,
                                                             @RequestBody @Valid InsumoRequest dto) {
        return ResponseEntity.ok(insumoService.actualizar(id, dto));
    }

    @Operation(summary = "Desactivar insumo", description = ROL_ADMIN + " Baja lógica (activo=false).", tags = "Insumos")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Insumo desactivado"),
            @ApiResponse(responseCode = "404", description = "No existe un insumo con ese id")
    })
    @DeleteMapping("/insumos/{id}")
    public ResponseEntity<Void> desactivarInsumo(@Parameter(description = "Id del insumo") @PathVariable UUID id) {
        insumoService.desactivar(id);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Reactivar insumo", description = ROL_ADMIN + " Revierte la baja lógica (activo=true).", tags = "Insumos")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Insumo reactivado", content = @Content(schema = @Schema(implementation = InsumoResponse.class))),
            @ApiResponse(responseCode = "404", description = "No existe un insumo con ese id")
    })
    @PatchMapping("/insumos/{id}/reactivar")
    public ResponseEntity<InsumoResponse> reactivarInsumo(@Parameter(description = "Id del insumo") @PathVariable UUID id) {
        return ResponseEntity.ok(insumoService.reactivar(id));
    }

    // --- Precios por litro ---
    // Auditoria CRUD (prioridad ALTA, bloqueante): antes no existia ninguna
    // forma de establecer el precio vigente desde la API (solo via INSERT
    // manual), pese a que Pago/Forecasting/PanelEconomico ya dependian de el.
    // Serie historica: sin editar/eliminar (ver PrecioLitroService).
    @Operation(
            summary = "Registrar nuevo precio por litro",
            description = ROL_ADMIN + " Serie histórica append-only: un precio nuevo no reemplaza al anterior, "
                    + "abre una vigencia distinta. El precio \"vigente\" en el resto del sistema es siempre "
                    + "el de `vigenteDesde` más reciente que ya llegó.",
            tags = "Precio por Litro"
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Precio registrado", content = @Content(schema = @Schema(implementation = PrecioLitroResponse.class))),
            @ApiResponse(responseCode = "400", description = "Datos inválidos (precio <= 0)"),
            @ApiResponse(responseCode = "409", description = "Ya existe un precio con esa misma vigenteDesde")
    })
    @PostMapping("/precios-litro")
    public ResponseEntity<PrecioLitroResponse> crearPrecioLitro(@RequestBody @Valid PrecioLitroRequest dto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(precioLitroService.crear(dto));
    }

    @Operation(summary = "Listar histórico de precios por litro (paginado)", description = ROL_ADMIN
            + " Orden descendente por vigenteDesde (desempate por id). page empieza en 0; size se acota a 1..100.", tags = "Precio por Litro")
    @ApiResponse(responseCode = "200", description = "Página del histórico", content = @Content(schema = @Schema(implementation = PaginaResponse.class)))
    @GetMapping("/precios-litro")
    public ResponseEntity<PaginaResponse<PrecioLitroResponse>> listarPreciosLitro(
            @Parameter(description = "Número de página, empieza en 0") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Tamaño de página (máx. 100)") @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(PaginaResponse.de(precioLitroService.listar(Paginacion.de(page, size))));
    }

    @Operation(summary = "Precio por litro vigente hoy", description = ROL_ADMIN
            + " El de vigenteDesde más reciente que ya llegó (<= hoy), la misma regla que usa la liquidación de pagos. "
            + "204 si todavía no hay ninguno vigente.", tags = "Precio por Litro")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Precio vigente", content = @Content(schema = @Schema(implementation = PrecioLitroResponse.class))),
            @ApiResponse(responseCode = "204", description = "No hay precio vigente")
    })
    @GetMapping("/precios-litro/vigente")
    public ResponseEntity<PrecioLitroResponse> precioLitroVigente() {
        return precioLitroService.vigente()
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    // --- Precios por presentación ---
    // V11__precio_presentacion.sql (Fase 2): extension de /precios (antes exclusivo de leche) por
    // PRESENTACION + modalidad (MAYORISTA/PROVEEDOR/PUBLICO) -- cada presentacion tiene sus propias
    // tarifas, nunca se deduce el precio de una a partir de otra. Serie historica append-only, igual que
    // precio-litro: sin editar/eliminar (ver PrecioQuesoService). No se exige configurar las tres
    // modalidades.
    @Operation(
            summary = "Registrar nuevo precio de presentación",
            description = ROL_ADMIN + " Serie histórica append-only por presentación + modalidad: un precio "
                    + "nuevo no reemplaza al anterior, abre una vigencia distinta. No es necesario configurar "
                    + "las tres modalidades.",
            tags = "Precio de Queso"
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Precio registrado", content = @Content(schema = @Schema(implementation = PrecioQuesoResponse.class))),
            @ApiResponse(responseCode = "400", description = "Datos inválidos (precio <= 0, modalidad no reconocida)"),
            @ApiResponse(responseCode = "404", description = "No existe una presentación con ese id"),
            @ApiResponse(responseCode = "409", description = "Ya existe un precio para esa presentación, modalidad y vigenteDesde")
    })
    @PostMapping("/precios-queso")
    public ResponseEntity<PrecioQuesoResponse> crearPrecioQueso(@RequestBody @Valid PrecioQuesoRequest dto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(precioQuesoService.crear(dto));
    }

    @Operation(
            summary = "Listar histórico de precios de queso",
            description = ROL_ADMIN + " Orden descendente por vigenteDesde. Con `presentacionId`, filtra al "
                    + "histórico de esa presentación únicamente (usado por el diálogo \"Ver histórico\" de "
                    + "/precios, para no traer el histórico completo del catálogo).",
            tags = "Precio de Queso"
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Histórico (completo, o de una presentación si se filtra)", content = @Content(array = @ArraySchema(schema = @Schema(implementation = PrecioQuesoResponse.class)))),
            @ApiResponse(responseCode = "404", description = "presentacionId no existe (solo si se envía)")
    })
    @GetMapping("/precios-queso")
    public ResponseEntity<List<PrecioQuesoResponse>> listarPreciosQueso(
            @Parameter(description = "Filtra al histórico de una sola presentación") @RequestParam(required = false) UUID presentacionId) {
        return ResponseEntity.ok(presentacionId != null
                ? precioQuesoService.listarPorPresentacion(presentacionId)
                : precioQuesoService.listar());
    }

    // Fase 2 (ampliacion: pantalla /precios reorganizada por familia con paginacion real). Distinto del
    // listado anterior (historico completo, sin paginar, usado por el dialogo de historial): esta vista es
    // una fila por PRESENTACION de producto activo (ver PresentacionRepository.buscarFilasPrecios), con la
    // situacion ya resuelta de las 3 modalidades -- pensada para la tabla principal de /precios, no para el
    // historico. familiaId/busqueda se aplican en el servidor antes de paginar (nunca se descarga todo el
    // catalogo para filtrar en el cliente).
    @Operation(
            summary = "Listar precios por presentación (paginado, organizado por familia)",
            description = ROL_ADMIN + " Una fila por presentación de producto activo; un producto activo "
                    + "sin presentaciones aparece como una fila informativa. `familiaId` y `busqueda` "
                    + "(coincide con el nombre del producto o de la presentación) son opcionales y se "
                    + "aplican en el servidor antes de paginar. `page` (default 0) y `size` (default 20, "
                    + "máx. 100).",
            tags = "Precio de Queso"
    )
    @ApiResponse(responseCode = "200", description = "Página de filas producto+presentación", content = @Content(schema = @Schema(implementation = PaginaResponse.class)))
    @GetMapping("/precios-queso/presentaciones")
    public ResponseEntity<PaginaResponse<FilaPrecioPresentacionResponse>> listarPreciosPorPresentacion(
            @Parameter(description = "Filtrar por familia") @RequestParam(required = false) UUID familiaId,
            @Parameter(description = "Busca en el nombre del producto o de la presentación") @RequestParam(required = false) String busqueda,
            @Parameter(description = "Número de página, empieza en 0") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Tamaño de página (máx. 100)") @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100));
        String busquedaNormalizada = (busqueda == null || busqueda.isBlank()) ? null : busqueda.trim();
        return ResponseEntity.ok(precioQuesoService.listarPaginado(familiaId, busquedaNormalizada, pageable));
    }

    // --- Roles (solo lectura, catalogo fijo) ---
    // Auditoria CRUD (prioridad MEDIA): sin esto el frontend tenia que
    // hardcodear la lista de roles para el formulario de alta de Usuario.
    @Operation(
            summary = "Listar roles",
            description = ROL_ADMIN + " Catálogo fijo sembrado por schema.sql: ACOPIADOR, CALIDAD, RECEPCION, "
                    + "PRODUCCION, VENTAS, ADMIN. Solo lectura, para poblar el selector de rol al crear/editar un usuario.",
            tags = "Roles"
    )
    @ApiResponse(responseCode = "200", description = "Roles disponibles", content = @Content(array = @ArraySchema(schema = @Schema(implementation = RolResponse.class))))
    @GetMapping("/roles")
    public ResponseEntity<List<RolResponse>> listarRoles() {
        return ResponseEntity.ok(rolService.listar());
    }

    // --- Configuracion del sistema ---
    @Operation(
            summary = "Listar parámetros de configuración",
            description = ROL_ADMIN + " Incluye, entre otros, UMBRAL_CONCILIACION_PCT, "
                    + "CAPACIDAD_PROCESAMIENTO_DIARIA_LITROS y UMBRAL_ANOMALIA_Z_SCORE.",
            tags = "Configuración del Sistema"
    )
    @ApiResponse(responseCode = "200", description = "Parámetros vigentes", content = @Content(array = @ArraySchema(schema = @Schema(implementation = ConfiguracionSistemaResponse.class))))
    @GetMapping("/configuracion")
    public ResponseEntity<List<ConfiguracionSistemaResponse>> listarConfiguracion() {
        return ResponseEntity.ok(configuracionSistemaService.listar());
    }

    @Operation(
            summary = "Editar un parámetro de configuración",
            description = ROL_ADMIN + " No se pueden crear ni eliminar claves: el catálogo de parámetros es fijo, solo se edita el valor.",
            tags = "Configuración del Sistema"
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Parámetro actualizado", content = @Content(schema = @Schema(implementation = ConfiguracionSistemaResponse.class))),
            @ApiResponse(responseCode = "400", description = "Valor inválido"),
            @ApiResponse(responseCode = "404", description = "No existe una configuración con esa clave")
    })
    @PutMapping("/configuracion/{clave}")
    public ResponseEntity<ConfiguracionSistemaResponse> actualizarConfiguracion(
            @Parameter(description = "Clave del parámetro, ej. UMBRAL_CONCILIACION_PCT") @PathVariable String clave,
            @RequestBody @Valid ConfiguracionSistemaUpdateRequest dto) {
        return ResponseEntity.ok(configuracionSistemaService.actualizar(clave, dto));
    }
}
