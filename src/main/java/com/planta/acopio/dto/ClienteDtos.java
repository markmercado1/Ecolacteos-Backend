package com.planta.acopio.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

// Fase H: padron de clientes, comprador de una venta o reserva e historial de compras. Endpoints EXCLUSIVOS de la web (el movil
// no los usa ni cambia su contrato). Para VENTAS los telefonos, DNI, RUC y correo llegan enmascarados (`datosCompletos` = false);
// ADMIN los ve completos.
public final class ClienteDtos {

    private ClienteDtos() {}

    /** forma: VERBAL_PRESENCIAL (en caja), ESCRITO, TELEFONICA o DIGITAL. */
    public record AutorizacionRegistro(boolean otorgada, String forma) {}

    /** Autorizacion SEPARADA para promociones. Nunca se asume: `otorgada` = false si no se pregunto o se nego. */
    public record AutorizacionComercial(boolean otorgada, List<String> medios, String forma) {}

    /**
     * Registro voluntario. Solo nombre y telefono son obligatorios (y la autorizacion del registro). `avisoVersion` = version del
     * aviso que se le leyo/mostro. `confirmadoSinCoincidencia` = el trabajador confirmo con el comprador que NO es ninguna de las
     * fichas que comparten su telefono (si las hay y no se confirma, 409).
     */
    public record ClienteRequest(
        String tipoPersona,
        @NotBlank String nombre,
        @NotBlank String telefono,
        String correo,
        String dni,
        String ruc,
        String razonSocial,
        @NotNull @Valid AutorizacionRegistro autorizacionRegistro,
        @Valid AutorizacionComercial comunicaciones,
        @NotBlank String avisoVersion,
        boolean confirmadoSinCoincidencia
    ) {}

    /** Edicion de datos (solo ADMIN). Los telefonos tienen su propio endpoint. */
    public record ClienteUpdateRequest(String tipoPersona, @NotBlank String nombre, String correo, String dni, String ruc, String razonSocial) {}

    public record TelefonoRequest(@NotBlank String telefono) {}

    public record MayoristaRequest(boolean mayorista, @NotBlank String motivo) {}

    /** Otorgar o revocar la autorizacion comercial. Revocar no necesita medios ni motivo. */
    public record ComercialRequest(boolean otorgada, List<String> medios, @NotBlank String forma, String avisoVersion, String nota) {}

    public record FusionRequest(@NotNull UUID destinoId, @NotBlank String motivo) {}

    /** Datos para buscar coincidencias antes de registrar o al vender: cualquier combinacion. */
    public record CoincidenciasRequest(String telefono, String dni, String ruc, String nombre) {}

    public record EstadoAutorizacion(boolean vigente, String estado, List<String> medios, String forma, LocalDateTime desde,
                                     String avisoVersion, boolean avisoCompleto) {}

    public record ClienteResponse(
        UUID id,
        String tipoPersona,
        String nombre,
        String razonSocial,
        String dni,
        String ruc,
        String correo,
        List<String> telefonos,
        boolean mayorista,
        boolean activo,
        UUID fusionadoEnId,
        LocalDateTime creadoEn,
        boolean datosCompletos,
        EstadoAutorizacion registro,
        EstadoAutorizacion comunicaciones
    ) {}

    public record ConsentimientoResponse(String finalidad, String estado, List<String> medios, String forma, String avisoVersion,
                                         boolean avisoCompleto, String registradoPor, LocalDateTime registradoEn, String nota) {}

    public record FusionResponse(UUID origenId, UUID destinoId, String motivo, String usuario, int ventasMovidas, int reservasMovidas,
                                 int telefonosAgregados, LocalDateTime creadoEn) {}

    /** Comprador elegido para una venta o una reserva. tipo: CLIENTE o PROVEEDOR. */
    public record CompradorRef(@NotBlank String tipo, @NotNull UUID id) {}

    /** Comprador tal como quedo en una venta (copia historica) o en una reserva. */
    public record CompradorResumen(String tipo, UUID id, String nombre, String ruc) {}

    /**
     * Posible comprador. coincidencia: DOCUMENTO (DNI/RUC exacto: misma persona), TELEFONO (comparte un telefono: CONFIRMAR con el
     * comprador, puede ser otra persona) o NOMBRE. Telefono y documento siempre enmascarados.
     */
    public record CompradorSugerencia(String tipo, UUID id, String nombre, String telefono, String documento, boolean mayorista,
                                      boolean activo, String coincidencia) {}

    public record LineaCompra(String producto, String presentacion, int cantidad, BigDecimal subtotal) {}

    public record CompraResponse(UUID ventaId, LocalDate fecha, String canal, BigDecimal total, UUID reservaId, String reservaCodigo,
                                 List<LineaCompra> lineas) {}

    /** Indicadores con ventas reales asociadas a esa identidad. `anio` = año calendario en curso (America/Lima). */
    public record ComprasResumen(int anio, long totalCompras, BigDecimal montoTotal, long comprasAnio, BigDecimal montoAnio,
                                 LocalDate ultimaCompra, long desdeReserva) {}

    public record ReservaDeComprador(UUID id, String codigo, String estado, LocalDate fechaRetiro, BigDecimal total, UUID ventaId) {}

    public record HistorialClienteResponse(ComprasResumen compras, List<ReservaDeComprador> reservas) {}
}
