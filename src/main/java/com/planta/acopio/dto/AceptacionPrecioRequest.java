package com.planta.acopio.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

// Fase 4: constancia de que el CLIENTE acepto el importe (cuando una linea tenia "Precio a confirmar" o el precio cambio).
// La registra VENTAS/ADMIN indicando por que medio lo acepto el cliente; queda con usuario, fecha y el importe aceptado.
public record AceptacionPrecioRequest(
    @NotBlank @Pattern(regexp = "TELEFONO|WHATSAPP|PRESENCIAL|OTRO", message = "medio debe ser TELEFONO, WHATSAPP, PRESENCIAL u OTRO") String medio,
    @Size(max = 300) String nota
) {}
