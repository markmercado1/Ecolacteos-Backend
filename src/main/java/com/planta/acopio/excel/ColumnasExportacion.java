package com.planta.acopio.excel;

import com.planta.acopio.dto.AlertaAnomaliaResponse;
import com.planta.acopio.dto.AnalisisBandejaResponse;
import com.planta.acopio.dto.LoteInsumoResponse;
import com.planta.acopio.dto.MovimientoKardexResponse;
import com.planta.acopio.dto.ClienteDtos.ClienteResponse;
import com.planta.acopio.dto.ConfiguracionSistemaResponse;
import com.planta.acopio.dto.DetalleSalidaResponse;
import com.planta.acopio.dto.DetalleVentaResponse;
import com.planta.acopio.dto.FamiliaResponse;
import com.planta.acopio.dto.FilaPrecioPresentacionResponse;
import com.planta.acopio.dto.FilaStockResponse;
import com.planta.acopio.dto.InsumoResponse;
import com.planta.acopio.dto.JornadaBandejaResponse;
import com.planta.acopio.dto.LoteProduccionResponse;
import com.planta.acopio.dto.MotivoObservacionResponse;
import com.planta.acopio.dto.PagoResponse;
import com.planta.acopio.dto.PrecioLitroResponse;
import com.planta.acopio.dto.PrecioModalidadResponse;
import com.planta.acopio.dto.PresentacionResponse;
import com.planta.acopio.dto.ProductoResponse;
import com.planta.acopio.dto.ProveedorAdminResponse;
import com.planta.acopio.dto.RecepcionPlantaResponse;
import com.planta.acopio.dto.RecetaResponse;
import com.planta.acopio.dto.RegistroAcopioResponse;
import com.planta.acopio.dto.ReservaResumenResponse;
import com.planta.acopio.dto.UnidadResponse;
import com.planta.acopio.dto.UsuarioResponse;
import com.planta.acopio.dto.VentaResumenResponse;
import com.planta.acopio.dto.ZonaResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.planta.acopio.excel.ColumnaExcel.decimal;
import static com.planta.acopio.excel.ColumnaExcel.entero;
import static com.planta.acopio.excel.ColumnaExcel.fecha;
import static com.planta.acopio.excel.ColumnaExcel.fechaHora;
import static com.planta.acopio.excel.ColumnaExcel.siNo;
import static com.planta.acopio.excel.ColumnaExcel.texto;

/**
 * Columnas de cada exportacion: lo que se ve en la tabla del panel, mas lo que contabilidad o gerencia suele pedir.
 * Se arman SOLO con campos que el DTO de la tabla ya expone, de modo que un rol nunca recibe en el Excel un dato que
 * no podria ver en pantalla (p. ej. DNI y telefono de proveedores solo salen para ADMIN: su endpoint es ADMIN).
 */
public final class ColumnasExportacion {

    private ColumnasExportacion() {
    }

    public static final List<ColumnaExcel<ProveedorAdminResponse>> PROVEEDORES = List.of(
            texto("Proveedor", 36, ProveedorAdminResponse::nombre),
            texto("Tipo de persona", 16, p -> "EMPRESA".equals(p.tipoPersona()) ? "Empresa" : "Persona natural"),
            texto("DNI", 14, ProveedorAdminResponse::dni),
            texto("RUC", 14, ProveedorAdminResponse::ruc),
            texto("Razón social", 36, ProveedorAdminResponse::razonSocial),
            texto("Teléfono", 18, ProveedorAdminResponse::telefono),
            texto("Zona actual", 22, ProveedorAdminResponse::zonaActualNombre),
            siNo("Activo", ProveedorAdminResponse::activo)
    );

    /**
     * Refactor clientes: padron de clientes (solo ADMIN, datos completos). Mismas columnas que la tabla mas lo que pide gerencia; el
     * tipo de cliente y el canal habitual van en columnas separadas (no son lo mismo).
     */
    public static final List<ColumnaExcel<ClienteResponse>> CLIENTES = List.of(
            texto("Cliente", 34, ClienteResponse::nombre),
            texto("Tipo de persona", 16, c -> "EMPRESA".equals(c.tipoPersona()) ? "Empresa" : "Persona natural"),
            texto("Razón social", 34, ClienteResponse::razonSocial),
            texto("DNI", 12, ClienteResponse::dni),
            texto("RUC", 14, ClienteResponse::ruc),
            texto("Teléfonos", 28, c -> String.join(" · ", c.telefonos())),
            texto("Correo", 28, ClienteResponse::correo),
            texto("Tipo de cliente", 14, c -> c.mayorista() ? "Mayorista" : "Regular"),
            texto("Canal habitual", 14, c -> "MAYORISTA".equals(c.canalHabitual()) ? "Mayorista" : "Público"),
            texto("Promociones", 26, c -> c.comunicaciones().vigente() ? "Sí (" + String.join(", ", c.comunicaciones().medios()) + ")" : "No"),
            entero("Reservas activas", c -> c.reservas().activas()),
            fecha("Próximo retiro", c -> c.reservas().proximoRetiro()),
            fechaHora("Registrado", ClienteResponse::creadoEn),
            siNo("Activo", ClienteResponse::activo)
    );

    public static final List<ColumnaExcel<RegistroAcopioResponse>> ACOPIOS = List.of(
            fechaHora("Fecha y hora", RegistroAcopioResponse::fechaHora),
            texto("Proveedor", 36, RegistroAcopioResponse::proveedorNombre),
            decimal("Litros", RegistroAcopioResponse::litros),
            texto("Observación", 28, RegistroAcopioResponse::motivoObservacion),
            siNo("Litros dictados por voz", RegistroAcopioResponse::litrosPorVoz),
            decimal("Latitud", 6, RegistroAcopioResponse::gpsLat),
            decimal("Longitud", 6, RegistroAcopioResponse::gpsLng),
            siNo("Con foto", r -> r.fotoUrl() != null && !r.fotoUrl().isBlank()),
            siNo("Disponible para lote", RegistroAcopioResponse::disponibleParaLote),
            fechaHora("Sincronizado", RegistroAcopioResponse::sincronizadoEn)
    );

    public static final List<ColumnaExcel<PagoResponse>> PAGOS = List.of(
            texto("Proveedor", 36, PagoResponse::proveedorNombre),
            fecha("Semana desde", PagoResponse::semanaInicio),
            fecha("Semana hasta", PagoResponse::semanaFin),
            decimal("Litros liquidados", PagoResponse::litrosTotales),
            decimal("Precio por litro original", PagoResponse::precioLitro),
            decimal("Importe original", PagoResponse::total),
            decimal("Precio por litro efectivo", PagoResponse::precioLitroEfectivo),
            decimal("Total a pagar", PagoResponse::totalAPagar),
            siNo("Comprobante generado", PagoResponse::comprobanteGenerado)
    );

    /** Una linea de una venta, con su cabecera. El total de la venta solo se repite en la primera linea (no se duplica al sumar). */
    public record LineaVenta(VentaResumenResponse venta, DetalleVentaResponse detalle, boolean primera) {
    }

    public static final List<ColumnaExcel<LineaVenta>> VENTAS = List.of(
            fecha("Fecha", l -> l.venta().fecha()),
            texto("Canal", 14, l -> l.venta().tipoCliente()),
            texto("Producto", 28, l -> l.detalle().productoNombre()),
            texto("Presentación", 28, l -> l.detalle().presentacionNombre()),
            entero("Cantidad", l -> l.detalle().cantidad()),
            decimal("Precio unitario", l -> l.detalle().precioUnitario()),
            decimal("Subtotal de la línea", l -> l.detalle().subtotal()),
            decimal("Total de la venta", l -> l.primera() ? l.venta().total() : null),
            // Fase G: foto tributaria de la linea; vacio = sin determinar (ventas anteriores o calculo de IGV apagado).
            texto("Afectación IGV", 16, l -> l.detalle().afectacionIgv()),
            decimal("Valor de venta", l -> l.detalle().valorVenta()),
            decimal("IGV incluido", l -> l.detalle().igv()),
            // Fase H: nombre del comprador usado en la venta (vacio = cliente varios); sin documentos ni telefonos
            texto("Comprador", 30, l -> l.primera() && l.venta().comprador() != null ? l.venta().comprador().nombre() : null)
    );

    /** Aplana ventas en lineas: una fila de Excel por linea vendida. */
    public static List<LineaVenta> lineasDe(List<VentaResumenResponse> ventas) {
        List<LineaVenta> lineas = new ArrayList<>();
        for (VentaResumenResponse venta : ventas) {
            List<DetalleVentaResponse> detalles = venta.detalles();
            if (detalles == null || detalles.isEmpty()) {
                lineas.add(new LineaVenta(venta, new DetalleVentaResponse(null, null, null, null, null, null), true));
                continue;
            }
            for (int i = 0; i < detalles.size(); i++) {
                lineas.add(new LineaVenta(venta, detalles.get(i), i == 0));
            }
        }
        return lineas;
    }

    // ------------------------------------------------------------------------------------------ fase 3

    public static final List<ColumnaExcel<ReservaResumenResponse>> RESERVAS = List.of(
            texto("Código", 14, ReservaResumenResponse::codigo),
            texto("Cliente", 30, ReservaResumenResponse::nombreCliente),
            texto("Teléfono", 16, ReservaResumenResponse::telefono),
            texto("Estado", 14, ReservaResumenResponse::estado),
            fecha("Fecha de retiro", ReservaResumenResponse::fechaRetiro),
            fechaHora("Creada", ReservaResumenResponse::creadoEn),
            fechaHora("Vence", ReservaResumenResponse::expiraEn),
            entero("Unidades", ReservaResumenResponse::unidades),
            // Con precio por confirmar no hay importe: se deja vacio en vez de inventar uno.
            decimal("Total", r -> r.precioAConfirmar() ? null : r.total()),
            siNo("Precio por confirmar", ReservaResumenResponse::precioAConfirmar),
            siNo("Requiere aceptación", ReservaResumenResponse::requiereAceptacion),
            siNo("Precio aceptado", ReservaResumenResponse::precioAceptado),
            siNo("Sin cobertura", ReservaResumenResponse::sinCobertura)
    );

    /** La recepcion solo trae el id de la unidad: la placa se resuelve con el mapa que arma quien exporta. */
    public static List<ColumnaExcel<RecepcionPlantaResponse>> recepciones(Map<UUID, String> placasPorUnidad) {
        return List.of(
                fecha("Fecha", RecepcionPlantaResponse::fecha),
                texto("Turno", 12, RecepcionPlantaResponse::turno),
                texto("Unidad", 14, r -> placasPorUnidad.get(r.unidadId())),
                decimal("Litros en campo", RecepcionPlantaResponse::litrosCampo),
                decimal("Litros en planta", RecepcionPlantaResponse::litrosPlanta),
                decimal("Diferencia (L)", RecepcionPlantaResponse::diferenciaLitros),
                decimal("Diferencia (%)", RecepcionPlantaResponse::diferenciaPct),
                texto("Estado", 14, RecepcionPlantaResponse::estado),
                fechaHora("Hora de llegada", RecepcionPlantaResponse::horaLlegada),
                texto("Observación", 30, RecepcionPlantaResponse::observacion)
        );
    }

    public static final List<ColumnaExcel<LoteProduccionResponse>> LOTES = List.of(
            fecha("Fecha", LoteProduccionResponse::fecha),
            texto("Producto", 28, LoteProduccionResponse::productoNombre),
            texto("Estado", 16, LoteProduccionResponse::estado),
            decimal("Leche usada (L)", LoteProduccionResponse::litrosUsados),
            texto("Receta", 24, LoteProduccionResponse::recetaNombre),
            decimal("Cantidad esperada", LoteProduccionResponse::cantidadEsperada),
            texto("Presentación esperada", 24, LoteProduccionResponse::presentacionEsperadaNombre),
            texto("Obtenido", 34, l -> resumenSalidas(l.detalles())),
            decimal("Rendimiento (%)", LoteProduccionResponse::rendimientoPct),
            decimal("Rendimiento esperado (%)", LoteProduccionResponse::rendimientoEsperadoPct),
            decimal("Diferencia", LoteProduccionResponse::diferencia),
            fechaHora("Iniciado", LoteProduccionResponse::iniciadoEn),
            fechaHora("Finalizado", LoteProduccionResponse::finalizadoEn)
    );

    private static String resumenSalidas(List<DetalleSalidaResponse> detalles) {
        if (detalles == null || detalles.isEmpty()) {
            return null;
        }
        return detalles.stream().map(d -> d.presentacionNombre() + " x " + d.cantidadObtenida()).collect(Collectors.joining("; "));
    }


    public static final List<ColumnaExcel<FilaStockResponse>> STOCK = List.of(
            texto("Familia", 20, FilaStockResponse::familiaNombre),
            texto("Producto", 28, FilaStockResponse::productoNombre),
            texto("Presentación", 28, FilaStockResponse::presentacionNombre),
            texto("Se cuenta en", 14, FilaStockResponse::unidadConteo),
            entero("Existencias", FilaStockResponse::stock),
            siNo("Controla vencimiento", FilaStockResponse::controlaVencimiento),
            entero("Vendible", FilaStockResponse::stockVendible),
            entero("Reservado", FilaStockResponse::stockComprometido),
            entero("Libre para vender", FilaStockResponse::stockLibre),
            siNo("Producto activo", FilaStockResponse::productoActivo),
            siNo("Presentación activa", FilaStockResponse::presentacionActivo)
    );

    public static final List<ColumnaExcel<InsumoResponse>> INSUMOS = List.of(
            texto("Insumo", 30, InsumoResponse::nombre),
            texto("Unidad de medida", 18, InsumoResponse::unidadMedida),
            decimal("Stock", InsumoResponse::stock),
            siNo("Activo", InsumoResponse::activo)
    );

    public static final List<ColumnaExcel<AnalisisBandejaResponse>> CALIDAD_ANALIZADOS = List.of(
            fechaHora("Fecha de entrega", AnalisisBandejaResponse::fechaHora),
            texto("Proveedor", 36, AnalisisBandejaResponse::proveedorNombre),
            decimal("Litros", AnalisisBandejaResponse::litros),
            texto("Resultado", 16, AnalisisBandejaResponse::resultado)
    );

    public static final List<ColumnaExcel<AlertaAnomaliaResponse>> ALERTAS_ANOMALIA = List.of(
            fechaHora("Fecha", AlertaAnomaliaResponse::creadoEn),
            texto("Proveedor", 36, AlertaAnomaliaResponse::proveedorNombre),
            texto("Tipo", 24, AlertaAnomaliaResponse::tipo),
            decimal("z-score", AlertaAnomaliaResponse::zScore),
            texto("Severidad", 14, AlertaAnomaliaResponse::severidad)
    );

    public static final List<ColumnaExcel<JornadaBandejaResponse>> JORNADAS = List.of(
            texto("Acopiador", 28, JornadaBandejaResponse::acopiadorNombre),
            texto("Vehículo", 14, JornadaBandejaResponse::unidadPlaca),
            texto("Estado", 22, JornadaBandejaResponse::estado),
            fechaHora("Inicio", JornadaBandejaResponse::horaInicio),
            fechaHora("Fin", JornadaBandejaResponse::horaFin),
            entero("Acopios", JornadaBandejaResponse::cantidadAcopios),
            entero("Proveedores", JornadaBandejaResponse::cantidadProveedores),
            decimal("Litros esperados", JornadaBandejaResponse::litrosEsperados)
    );

    // ------------------------------------------------------------------------------------ administracion

    /** Nunca incluye la contrasena ni su hash: UsuarioResponse no los tiene. */
    public static final List<ColumnaExcel<UsuarioResponse>> USUARIOS = List.of(
            texto("Nombre", 30, UsuarioResponse::nombre),
            texto("Correo de acceso", 34, UsuarioResponse::email),
            texto("Rol", 16, UsuarioResponse::rolNombre),
            siNo("Activo", UsuarioResponse::activo)
    );

    public static final List<ColumnaExcel<PrecioLitroResponse>> PRECIOS_LITRO = List.of(
            fecha("Vigente desde", PrecioLitroResponse::vigenteDesde),
            decimal("Precio por litro", 3, PrecioLitroResponse::precio)
    );

    public static final List<ColumnaExcel<FilaPrecioPresentacionResponse>> PRECIOS_PRESENTACION = preciosPresentacion();

    private static List<ColumnaExcel<FilaPrecioPresentacionResponse>> preciosPresentacion() {
        List<ColumnaExcel<FilaPrecioPresentacionResponse>> columnas = new ArrayList<>(List.of(
                texto("Familia", 20, FilaPrecioPresentacionResponse::familiaNombre),
                texto("Producto", 28, FilaPrecioPresentacionResponse::productoNombre),
                texto("Presentación", 28, FilaPrecioPresentacionResponse::presentacionNombre)));
        agregarModalidad(columnas, "Mayorista", FilaPrecioPresentacionResponse::mayorista);
        agregarModalidad(columnas, "Proveedor", FilaPrecioPresentacionResponse::proveedor);
        agregarModalidad(columnas, "Público", FilaPrecioPresentacionResponse::publico);
        return List.copyOf(columnas);
    }

    /**
     * Columnas de la exportacion de precios con UNA triada (precio, vigente desde, situacion) por cada tipo del catalogo: los tres originales
     * primero y en su orden de siempre (Mayorista, Proveedor, Publico) para que el archivo actual no cambie, y despues los tipos nuevos por codigo.
     */
    public static List<ColumnaExcel<FilaPrecioPresentacionResponse>> preciosPresentacion(List<com.planta.acopio.dto.TipoPrecioDtos.TipoPrecioResponse> tipos) {
        List<ColumnaExcel<FilaPrecioPresentacionResponse>> columnas = new ArrayList<>(List.of(
                texto("Familia", 20, FilaPrecioPresentacionResponse::familiaNombre),
                texto("Producto", 28, FilaPrecioPresentacionResponse::productoNombre),
                texto("Presentación", 28, FilaPrecioPresentacionResponse::presentacionNombre)));
        List<String> originales = List.of("MAYORISTA", "PROVEEDOR", "PUBLICO");
        List<com.planta.acopio.dto.TipoPrecioDtos.TipoPrecioResponse> ordenados = new ArrayList<>();
        for (String codigo : originales) tipos.stream().filter(t -> t.codigo().equals(codigo)).findFirst().ifPresent(ordenados::add);
        tipos.stream().filter(t -> !originales.contains(t.codigo())).sorted(java.util.Comparator.comparing(t -> t.codigo())).forEach(ordenados::add);
        for (var t : ordenados) {
            String codigo = t.codigo();
            agregarModalidad(columnas, t.nombre(), f -> f.porTipo() == null ? null : f.porTipo().get(codigo));
        }
        return List.copyOf(columnas);
    }

    private static void agregarModalidad(List<ColumnaExcel<FilaPrecioPresentacionResponse>> columnas, String nombre,
                                         Function<FilaPrecioPresentacionResponse, PrecioModalidadResponse> modalidad) {
        columnas.add(decimal(nombre, f -> campo(modalidad.apply(f), PrecioModalidadResponse::precio)));
        columnas.add(fecha(nombre + ": vigente desde", f -> campo(modalidad.apply(f), PrecioModalidadResponse::vigenteDesde)));
        columnas.add(texto(nombre + ": situación", 16, f -> campo(modalidad.apply(f), PrecioModalidadResponse::estado)));
    }

    private static Object campo(PrecioModalidadResponse m, Function<PrecioModalidadResponse, Object> extractor) {
        return m == null ? null : extractor.apply(m);
    }

    public static final List<ColumnaExcel<ZonaResponse>> ZONAS = List.of(
            texto("Zona", 30, ZonaResponse::nombre),
            siNo("Activa", ZonaResponse::activa)
    );

    public static final List<ColumnaExcel<MotivoObservacionResponse>> MOTIVOS_OBSERVACION = List.of(
            texto("Motivo", 50, MotivoObservacionResponse::descripcion)
    );

    public static final List<ColumnaExcel<FamiliaResponse>> FAMILIAS = List.of(
            texto("Familia", 30, FamiliaResponse::nombre),
            siNo("Activa", FamiliaResponse::activo)
    );

    public static final List<ColumnaExcel<ProductoResponse>> PRODUCTOS = List.of(
            texto("Producto", 30, ProductoResponse::nombre),
            texto("Familia", 20, ProductoResponse::familiaNombre),
            decimal("Rendimiento esperado (%)", ProductoResponse::rendimientoEsperadoPct),
            texto("Ciclo de capital", 16, ProductoResponse::cicloCapital),
            texto("Presentaciones", 50, p -> nombresPresentaciones(p.presentaciones())),
            siNo("Activo", ProductoResponse::activo)
    );

    private static String nombresPresentaciones(List<PresentacionResponse> presentaciones) {
        if (presentaciones == null || presentaciones.isEmpty()) {
            return null;
        }
        return presentaciones.stream().map(PresentacionResponse::nombre).collect(Collectors.joining("; "));
    }

    public static final List<ColumnaExcel<RecetaResponse>> RECETAS = List.of(
            texto("Receta", 28, RecetaResponse::nombre),
            texto("Producto", 28, RecetaResponse::productoNombre),
            texto("Presentación", 28, RecetaResponse::presentacionNombre),
            decimal("Leche base (L)", RecetaResponse::litrosBase),
            decimal("Rendimiento base", RecetaResponse::rendimientoBase),
            texto("Insumos por leche base", 50, r -> resumenInsumos(r.insumos())),
            siNo("Activa", RecetaResponse::activo)
    );

    private static String resumenInsumos(List<RecetaResponse.Insumo> insumos) {
        if (insumos == null || insumos.isEmpty()) {
            return null;
        }
        return insumos.stream()
                .map(i -> i.insumoNombre() + " " + (i.cantidad() == null ? "" : i.cantidad().stripTrailingZeros().toPlainString()) + " " + i.unidadMedida())
                .collect(Collectors.joining("; "));
    }

    public static final List<ColumnaExcel<ConfiguracionSistemaResponse>> CONFIGURACION = List.of(
            texto("Parámetro", 36, ConfiguracionSistemaResponse::clave),
            texto("Valor", 24, ConfiguracionSistemaResponse::valor),
            texto("Descripción", 70, ConfiguracionSistemaResponse::descripcion)
    );

    /** La unidad solo trae ids de zona: los nombres se resuelven con el mapa que arma quien exporta. */
    public static List<ColumnaExcel<UnidadResponse>> unidades(Map<UUID, String> zonasPorId) {
        return List.of(
                texto("Placa", 14, UnidadResponse::placa),
                decimal("Capacidad (t)", UnidadResponse::capacidadTon),
                texto("Zona", 22, u -> u.zonaId() == null ? null : zonasPorId.get(u.zonaId())),
                texto("Responsable", 30, UnidadResponse::responsableNombre)
        );
    }

    // ---------------------------------------------------------------------------------------- lotes de insumo (V43 a V46)

    private static final Map<String, String> ETIQUETA_MOV_INSUMO = Map.ofEntries(
            Map.entry("SALDO_INICIAL", "Saldo inicial"), Map.entry("INGRESO", "Ingreso"), Map.entry("SALIDA_PRODUCCION", "Consumo en producción"),
            Map.entry("AJUSTE", "Conteo físico"), Map.entry("SALIDA_VENCIMIENTO", "Salida: vencido"), Map.entry("SALIDA_DETERIORO", "Salida: deterioro"),
            Map.entry("SALIDA_OTRA", "Salida: otra"), Map.entry("SALIDA_MERMA", "Salida: merma"), Map.entry("SALIDA_DERRAME", "Salida: derrame"),
            Map.entry("SALIDA_DEVOLUCION_PROVEEDOR", "Salida: devolución a proveedor"), Map.entry("SALIDA_CONSUMO_MANUAL", "Salida: consumo manual"),
            Map.entry("ANULACION_INGRESO", "Anulación de ingreso"), Map.entry("TRANSFERENCIA_LOTE", "Transferencia entre lotes"));

    private static final Map<String, String> ETIQUETA_DOCUMENTO = Map.of("GUIA", "Guía", "FACTURA", "Factura", "BOLETA", "Boleta", "OTRO", "Otro", "SIN_DOCUMENTO", "Sin documento");

    private static final Map<String, String> ETIQUETA_ESTADO_LOTE = Map.of("VIGENTE", "Vigente", "POR_VENCER", "Por vencer", "VENCIDO", "Vencido", "AGOTADO", "Agotado");

    /** Kardex de un insumo: las columnas de siempre y, por fila, su lote, proveedor, vencimiento y el documento de ingreso. */
    public static final List<ColumnaExcel<MovimientoKardexResponse>> KARDEX_INSUMO = List.of(
            fechaHora("Fecha", MovimientoKardexResponse::fecha),
            texto("Movimiento", 28, m -> ETIQUETA_MOV_INSUMO.getOrDefault(m.tipoMovimiento(), m.tipoMovimiento())),
            texto("Documento", 34, MovimientoKardexResponse::referencia),
            decimal("Entrada", MovimientoKardexResponse::entrada),
            decimal("Salida", MovimientoKardexResponse::salida),
            decimal("Saldo", MovimientoKardexResponse::saldo),
            texto("Usuario", 24, MovimientoKardexResponse::usuarioNombre),
            texto("Motivo", 36, MovimientoKardexResponse::motivo),
            texto("Lote", 22, MovimientoKardexResponse::loteCodigo),
            texto("Proveedor", 30, MovimientoKardexResponse::proveedorNombre),
            fecha("Vencimiento", MovimientoKardexResponse::fechaVencimiento),
            texto("Tipo de documento", 18, m -> m.ingresoDocumentoTipo() == null ? null : ETIQUETA_DOCUMENTO.getOrDefault(m.ingresoDocumentoTipo(), m.ingresoDocumentoTipo())),
            texto("Número de documento", 22, MovimientoKardexResponse::ingresoDocumentoNumero));

    /** Lista de lotes de insumo con su origen y estado. */
    public static final List<ColumnaExcel<LoteInsumoResponse>> LOTES_INSUMO = List.of(
            texto("Código de lote", 22, LoteInsumoResponse::codigo),
            texto("Insumo", 28, LoteInsumoResponse::insumoNombre),
            texto("Proveedor", 30, LoteInsumoResponse::proveedorNombre),
            texto("RUC", 14, LoteInsumoResponse::proveedorRuc),
            texto("Lote del proveedor", 22, LoteInsumoResponse::codigoProveedor),
            fecha("Fecha de ingreso", LoteInsumoResponse::fechaIngreso),
            fecha("Vencimiento", LoteInsumoResponse::fechaVencimiento),
            decimal("Cantidad inicial", LoteInsumoResponse::cantidadInicial),
            decimal("Disponible", LoteInsumoResponse::cantidadDisponible),
            texto("Unidad", 12, LoteInsumoResponse::unidadMedida),
            texto("Estado", 14, l -> ETIQUETA_ESTADO_LOTE.getOrDefault(l.estado(), l.estado())),
            texto("Documento de ingreso", 28, l -> l.tipoDocumento() == null ? null
                    : ETIQUETA_DOCUMENTO.getOrDefault(l.tipoDocumento(), l.tipoDocumento()) + (l.numeroDocumento() == null ? "" : " " + l.numeroDocumento())));
}
