package com.planta.acopio.dto;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
public record SalidaInventarioResponse(UUID id, UUID movimientoId, BigDecimal cantidad, String tipo,
        String motivo, String origenLote, UUID loteId, LocalDate fechaVencimiento,
        String usuarioNombre, LocalDateTime creadoEn) {}
