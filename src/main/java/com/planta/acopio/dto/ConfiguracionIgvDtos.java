package com.planta.acopio.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

// Fase G: configuracion del IGV (solo ADMIN, exclusivo de la web). Los precios son finales: el IGV se desglosa dentro del
// precio, nunca se suma. Nada se calcula mientras app.tributario.igv.calculo-habilitado sea false (estado PENDIENTE).
public final class ConfiguracionIgvDtos {

    private ConfiguracionIgvDtos() {}

    /** tasa en porcentaje (18.00 = 18 %). */
    public record TasaIgvRequest(@NotNull BigDecimal tasa, @NotNull LocalDate vigenteDesde, @NotBlank String motivo) {}

    public record TasaIgvResponse(UUID id, BigDecimal tasa, LocalDate vigenteDesde, String motivo, String usuarioNombre,
                                  LocalDateTime creadoEn) {}

    /** afectacion: GRAVADO, EXONERADO, INAFECTO o SIN_DEFINIR (para deshacer una clasificacion desde una fecha). */
    public record AfectacionIgvRequest(@NotBlank String afectacion, @NotNull LocalDate vigenteDesde, @NotBlank String motivo) {}

    public record AfectacionIgvResponse(UUID id, UUID presentacionId, String afectacion, LocalDate vigenteDesde, String motivo,
                                        String usuarioNombre, LocalDateTime creadoEn) {}

    /** Una fila por presentacion activa: su afectacion vigente hoy (SIN_DEFINIR si no tiene) y la proxima programada, si hay. */
    public record FilaAfectacionIgvResponse(UUID presentacionId, String presentacionNombre, UUID productoId, String productoNombre,
                                            String familiaNombre, String afectacionVigente, LocalDate vigenteDesde,
                                            String afectacionProgramada, LocalDate programadaDesde) {}

    /**
     * estado: PENDIENTE (calculo apagado: nada se desglosa, las ventas se registran como antes) o ACTIVO.
     * Los contadores son de presentaciones activas segun su afectacion vigente hoy.
     */
    public record EstadoIgvResponse(boolean calculoHabilitado, String estado, TasaIgvResponse tasaVigente, TasaIgvResponse tasaProgramada,
                                    long presentacionesActivas, long sinDefinir, long gravadas, long exoneradas, long inafectas,
                                    List<String> avisos) {}
}
