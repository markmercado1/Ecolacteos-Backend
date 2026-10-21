package com.planta.acopio.dto;

// GET /api/jornadas/mia/activa (ver JornadaController.obtenerMiJornadaActiva). `activa` es null cuando el
// acopiador autenticado no tiene ninguna jornada EN_RECOLECCION/REGRESANDO_A_PLANTA en el servidor --
// siempre 200 con este wrapper (nunca 204) para que el cliente pueda parsear la respuesta sin distinguir
// "sin body" de "error de red".
public record JornadaActivaPropiaResponse(JornadaResponse activa) {}
