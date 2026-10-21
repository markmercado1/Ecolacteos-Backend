package com.planta.acopio.dto;

import java.util.UUID;

// Auditoria CRUD (prioridad MEDIA): solo lectura -- rol es un catalogo fijo
// sembrado por schema.sql. Este endpoint existe unicamente para poblar el
// selector de roles del formulario de alta/edicion de Usuario en el frontend.
public record RolResponse(
    UUID id,
    String nombre
) {}
