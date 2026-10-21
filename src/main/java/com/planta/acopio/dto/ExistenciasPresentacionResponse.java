package com.planta.acopio.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

// Fase 2 (solo web): existencias de UNA presentacion separando lo fisico de lo vendible. Para un producto sin
// control de vencimiento: stockVendible = stockFisico, los demas campos en cero y `lotes` vacio. Para uno con
// control:  stockFisico = stockVendible + stockVencido + stockSinTrazabilidad. Lo "sin trazabilidad" (existencias
// anteriores a la activacion del control) y lo vencido NO se pueden vender.
public record ExistenciasPresentacionResponse(
    UUID presentacionId,
    boolean controlaVencimiento,
    int stockFisico,
    int stockVendible,
    int stockVencido,
    int stockSinTrazabilidad,
    // "Hoy" segun el servidor (zona America/Lima por defecto), con el que se decidio que esta vencido.
    LocalDate fechaReferencia,
    List<LoteExistenciaResponse> lotes
) {}
