package com.planta.acopio.dto;

import java.util.List;
import java.util.UUID;

// Fase 2 (solo web): ids de los productos que controlan vencimiento. La web lo usa para decidir si pide fecha de
// vencimiento al finalizar un lote y para mostrar el estado del control en el catalogo. Se mantiene aparte de
// ProductoResponse porque ese contrato lo comparte la sincronizacion del movil.
public record ProductosConControlResponse(List<UUID> productoIds) {}
