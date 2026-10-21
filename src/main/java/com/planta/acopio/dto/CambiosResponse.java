package com.planta.acopio.dto;

import java.time.Instant;
import java.util.List;

// GET /api/sync/cambios: catalogos + precios + comunicados + predicciones
// descargados por el dispositivo (CORE_DESIGN.md §12).
public record CambiosResponse(
    Instant generadoEn,
    List<ProveedorPublicoResponse> proveedores,
    java.math.BigDecimal precioLitroVigente,
    List<ComunicadoResponse> comunicados,
    List<PrediccionProveedorResponse> prediccionesProveedor,
    List<MotivoObservacionResponse> motivosObservacion,
    List<ProductoResponse> productos,
    // Segunda auditoria (flujos end-to-end §14-D): faltaba para que
    // ACOPIADOR/RECEPCION puedan resolver unidadId offline (RegistroAcopioDTO
    // y RecepcionPlantaRequest lo exigen, y antes no habia forma de conocerlo).
    List<UnidadResponse> unidades,
    // Feature "Produccion diaria": PRODUCCION necesita el catalogo de insumos (cuajo, sal, etc.) para el
    // formulario del informe diario sin el rol ADMIN que exige GET /api/admin/insumos -- mismo criterio ya
    // usado para tiposQueso. Campo aditivo al final del record: el movil deserializa con
    // ignoreUnknownKeys=true (network/JsonConfig.kt), no rompe.
    List<InsumoResponse> insumos,
    // Compatibilidad: la app movil exige este campo (lo reemplazo 'productos' en el backend, pero el
    // movil no se actualizo). Sin el, "Actualizar catalogo" falla y no se descargan las unidades.
    List<TipoQuesoResponse> tiposQueso
) {}
