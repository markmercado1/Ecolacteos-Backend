package com.planta.acopio.controller;

import com.planta.acopio.dto.ZonaResponse;
import com.planta.acopio.excel.ColumnasExportacion;
import com.planta.acopio.excel.ExportacionExcelService;
import com.planta.acopio.excel.FuentePaginada;
import com.planta.acopio.excel.RespuestaExcel;
import com.planta.acopio.service.ConfiguracionSistemaService;
import com.planta.acopio.service.FamiliaService;
import com.planta.acopio.service.InsumoService;
import com.planta.acopio.service.MotivoObservacionService;
import com.planta.acopio.service.PrecioLitroService;
import com.planta.acopio.service.PrecioQuesoService;
import com.planta.acopio.service.ProductoService;
import com.planta.acopio.service.RecetaService;
import com.planta.acopio.service.UnidadService;
import com.planta.acopio.service.UsuarioService;
import com.planta.acopio.service.ZonaService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Exportacion a Excel de la administracion y los catalogos. Mismos roles y mismo contenido que sus listados (/api/admin/...):
 * es lo que la pantalla muestra, no un volcado de la base. Las listas que el backend entrega completas aceptan `busqueda`
 * y filtran con el criterio de las tablas (contiene, sin distinguir mayusculas ni acentos).
 */
@Tag(name = "Exportación a Excel", description = "Descarga de las tablas del panel en .xlsx: todas las filas del filtro y la búsqueda actuales.")
@RestController
@RequestMapping("/api/exportaciones")
public class ExportacionAdminController {

    private final ExportacionExcelService excel;
    private final UsuarioService usuarioService;
    private final PrecioLitroService precioLitroService;
    private final PrecioQuesoService precioQuesoService;
    private final ZonaService zonaService;
    private final MotivoObservacionService motivoService;
    private final FamiliaService familiaService;
    private final ProductoService productoService;
    private final InsumoService insumoService;
    private final RecetaService recetaService;
    private final ConfiguracionSistemaService configuracionService;
    private final UnidadService unidadService;

    public ExportacionAdminController(ExportacionExcelService excel, UsuarioService usuarioService,
                                      PrecioLitroService precioLitroService, PrecioQuesoService precioQuesoService,
                                      ZonaService zonaService, MotivoObservacionService motivoService,
                                      FamiliaService familiaService, ProductoService productoService,
                                      InsumoService insumoService, RecetaService recetaService,
                                      ConfiguracionSistemaService configuracionService, UnidadService unidadService) {
        this.excel = excel;
        this.usuarioService = usuarioService;
        this.precioLitroService = precioLitroService;
        this.precioQuesoService = precioQuesoService;
        this.zonaService = zonaService;
        this.motivoService = motivoService;
        this.familiaService = familiaService;
        this.productoService = productoService;
        this.insumoService = insumoService;
        this.recetaService = recetaService;
        this.configuracionService = configuracionService;
        this.unidadService = unidadService;
    }

    @Operation(summary = "Exportar usuarios", description = "Rol requerido: ADMIN. Mismos filtros que GET /api/admin/usuarios/paginado. Nunca incluye contraseñas.")
    @GetMapping("/usuarios")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Resource> usuarios(
            @Parameter(description = "Rol") @RequestParam(required = false) String rol,
            @Parameter(description = "Activo / inactivo") @RequestParam(required = false) Boolean activo,
            @Parameter(description = "Nombre o correo") @RequestParam(required = false) String busqueda) {
        return RespuestaExcel.de(excel.exportar("usuarios", "Usuarios", ColumnasExportacion.USUARIOS,
                pagina -> usuarioService.buscar(rol, activo, busqueda, pagina)));
    }

    @Operation(summary = "Exportar el histórico del precio por litro", description = "Rol requerido: ADMIN. Mismo contenido que GET /api/admin/precios-litro.")
    @GetMapping("/precios-litro")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Resource> preciosLitro() {
        return RespuestaExcel.de(excel.exportar("precios-litro", "Precio por litro", ColumnasExportacion.PRECIOS_LITRO,
                precioLitroService::listar));
    }

    @Operation(summary = "Exportar los precios por presentación", description = "Rol requerido: ADMIN. Mismos filtros que GET /api/admin/precios-queso/presentaciones.")
    @GetMapping("/precios-presentacion")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Resource> preciosPresentacion(
            @Parameter(description = "Filtrar por familia") @RequestParam(required = false) UUID familiaId,
            @Parameter(description = "Producto o presentación") @RequestParam(required = false) String busqueda) {
        return RespuestaExcel.de(excel.exportar("precios-presentacion", "Precios", ColumnasExportacion.preciosPresentacion(precioQuesoService.tiposDelCatalogo()),
                FuentePaginada.deRespuesta(pagina -> precioQuesoService.listarPaginado(familiaId, busqueda, pagina))));
    }

    @Operation(summary = "Exportar zonas", description = "Rol requerido: ADMIN. Mismo contenido que GET /api/admin/zonas.")
    @GetMapping("/zonas")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Resource> zonas(@Parameter(description = "Nombre de la zona") @RequestParam(required = false) String busqueda) {
        return RespuestaExcel.de(excel.exportarLista("zonas", "Zonas", ColumnasExportacion.ZONAS, zonaService.listarActivas(), busqueda));
    }

    @Operation(summary = "Exportar motivos de observación", description = "Rol requerido: ADMIN. Mismo contenido que GET /api/admin/motivos-observacion.")
    @GetMapping("/motivos-observacion")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Resource> motivos(@Parameter(description = "Descripción del motivo") @RequestParam(required = false) String busqueda) {
        return RespuestaExcel.de(excel.exportarLista("motivos-observacion", "Motivos", ColumnasExportacion.MOTIVOS_OBSERVACION,
                motivoService.listarActivos(), busqueda));
    }

    @Operation(summary = "Exportar familias de productos", description = "Rol requerido: ADMIN. Mismo contenido que GET /api/admin/familias.")
    @GetMapping("/familias")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Resource> familias(@Parameter(description = "Nombre de la familia") @RequestParam(required = false) String busqueda) {
        return RespuestaExcel.de(excel.exportarLista("familias", "Familias", ColumnasExportacion.FAMILIAS, familiaService.listarActivas(), busqueda));
    }

    @Operation(summary = "Exportar productos", description = "Rol requerido: ADMIN. Mismo contenido que GET /api/admin/productos.")
    @GetMapping("/productos")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Resource> productos(@Parameter(description = "Producto o familia") @RequestParam(required = false) String busqueda) {
        return RespuestaExcel.de(excel.exportarLista("productos", "Productos", ColumnasExportacion.PRODUCTOS, productoService.listarActivos(), busqueda));
    }

    @Operation(summary = "Exportar insumos", description = "Rol requerido: ADMIN. Mismo contenido que GET /api/admin/insumos.")
    @GetMapping("/insumos")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Resource> insumos(@Parameter(description = "Nombre del insumo") @RequestParam(required = false) String busqueda) {
        return RespuestaExcel.de(excel.exportarLista("insumos", "Insumos", ColumnasExportacion.INSUMOS, insumoService.listarActivos(), busqueda));
    }

    @Operation(summary = "Exportar recetas", description = "Rol requerido: PRODUCCION o ADMIN. Mismo contenido que GET /api/recetas.")
    @GetMapping("/recetas")
    @PreAuthorize("hasAnyRole('PRODUCCION','ADMIN')")
    public ResponseEntity<Resource> recetas(
            @Parameter(description = "Incluir recetas desactivadas") @RequestParam(defaultValue = "true") boolean incluirInactivas,
            @Parameter(description = "Receta o producto") @RequestParam(required = false) String busqueda) {
        return RespuestaExcel.de(excel.exportarLista("recetas", "Recetas", ColumnasExportacion.RECETAS,
                recetaService.listar(incluirInactivas), busqueda));
    }

    @Operation(summary = "Exportar la configuración del sistema", description = "Rol requerido: ADMIN. Mismo contenido que GET /api/admin/configuracion.")
    @GetMapping("/configuracion")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Resource> configuracion(@Parameter(description = "Clave, valor o descripción") @RequestParam(required = false) String busqueda) {
        return RespuestaExcel.de(excel.exportarLista("configuracion", "Configuración", ColumnasExportacion.CONFIGURACION,
                configuracionService.listar(), busqueda));
    }

    @Operation(summary = "Exportar unidades (vehículos)", description = "Rol requerido: ADMIN. Mismo contenido que GET /api/admin/unidades.")
    @GetMapping("/unidades")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Resource> unidades(@Parameter(description = "Placa, zona o responsable") @RequestParam(required = false) String busqueda) {
        // La unidad solo trae el id de la zona; el nombre que muestra la pantalla se resuelve aqui.
        Map<UUID, String> zonas = zonaService.listarActivas().stream()
                .collect(Collectors.toMap(ZonaResponse::id, ZonaResponse::nombre, (a, b) -> a));
        return RespuestaExcel.de(excel.exportarLista("unidades", "Unidades", ColumnasExportacion.unidades(zonas),
                unidadService.listar(), busqueda));
    }
}
