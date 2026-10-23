package com.planta.acopio.excel;

import com.planta.acopio.dto.DetalleVentaResponse;
import com.planta.acopio.dto.PagoResponse;
import com.planta.acopio.dto.ProveedorAdminResponse;
import com.planta.acopio.dto.RegistroAcopioResponse;
import com.planta.acopio.dto.VentaResumenResponse;

import java.util.ArrayList;
import java.util.List;

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
            texto("DNI", 14, ProveedorAdminResponse::dni),
            texto("Teléfono", 18, ProveedorAdminResponse::telefono),
            texto("Zona actual", 22, ProveedorAdminResponse::zonaActualNombre),
            siNo("Activo", ProveedorAdminResponse::activo)
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
            decimal("Total de la venta", l -> l.primera() ? l.venta().total() : null)
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
}
