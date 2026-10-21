package com.planta.acopio.dto;

// Ver AdminJornadaController.cerrarForzado. `motivo` es opcional -- si no viaja (o el body entero viene
// vacío), JornadaService.cerrarForzado usa un motivo genérico por defecto.
public record CerrarJornadaForzadoRequest(String motivo) {}
