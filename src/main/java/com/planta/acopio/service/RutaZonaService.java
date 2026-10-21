package com.planta.acopio.service;

import com.planta.acopio.dto.DefinirRutaZonaRequest;
import com.planta.acopio.dto.RutaItemRequest;
import com.planta.acopio.dto.RutaProveedorOrdenResponse;
import com.planta.acopio.entity.Proveedor;
import com.planta.acopio.entity.RutaProveedorOrden;
import com.planta.acopio.entity.Zona;
import com.planta.acopio.exception.RecursoNoEncontradoException;
import com.planta.acopio.exception.ValidacionNegocioException;
import com.planta.acopio.repository.ProveedorRepository;
import com.planta.acopio.repository.RutaProveedorOrdenRepository;
import com.planta.acopio.repository.ZonaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

// RF-RUT-04 (CORE_DESIGN.md §3, §7, §8): orden de paso por proveedor dentro
// de una zona, con hora estimada. La entidad RutaProveedorOrden y su UNIQUE
// (zona_id, orden) (mejora #5) ya existian en el schema desde Fase 1, pero
// nunca se implemento el service/controller que las administra -- el propio
// comentario de la entidad ("se administra desde 'definir ruta de zona'")
// describia esta funcionalidad sin que existiera.
//
// Semantica de reemplazo total (no upsert item por item): cada llamada a
// definirRuta() borra el orden previo de la zona y guarda el nuevo completo,
// dentro de la misma transaccion -- evita dejar numeros de orden repetidos o
// huecos a medio editar, que es justo lo que la mejora #5 buscaba prevenir.
@Service
public class RutaZonaService {

    private final RutaProveedorOrdenRepository repository;
    private final ZonaRepository zonaRepository;
    private final ProveedorRepository proveedorRepository;

    public RutaZonaService(RutaProveedorOrdenRepository repository, ZonaRepository zonaRepository,
                            ProveedorRepository proveedorRepository) {
        this.repository = repository;
        this.zonaRepository = zonaRepository;
        this.proveedorRepository = proveedorRepository;
    }

    @Transactional
    public List<RutaProveedorOrdenResponse> definirRuta(UUID zonaId, DefinirRutaZonaRequest dto) {
        Zona zona = zonaRepository.findById(zonaId)
                .orElseThrow(() -> new RecursoNoEncontradoException("Zona no encontrada: " + zonaId));

        Set<UUID> proveedorIdsVistos = new HashSet<>();
        Set<Integer> ordenesVistos = new HashSet<>();
        List<RutaProveedorOrden> nuevos = new ArrayList<>();

        for (RutaItemRequest item : dto.items()) {
            if (!proveedorIdsVistos.add(item.proveedorId())) {
                throw new ValidacionNegocioException(
                        "La ruta no puede incluir dos veces al mismo proveedor: " + item.proveedorId());
            }
            if (!ordenesVistos.add(item.orden())) {
                throw new ValidacionNegocioException(
                        "La ruta no puede repetir el numero de orden " + item.orden());
            }

            Proveedor proveedor = proveedorRepository.findById(item.proveedorId())
                    .orElseThrow(() -> new RecursoNoEncontradoException("Proveedor no encontrado: " + item.proveedorId()));

            // La ruta de una zona solo tiene sentido para proveedores actualmente
            // asignados a esa zona (RF-RUT-02 ya permite reasignar; si un
            // proveedor cambio de zona, debe salir de la ruta anterior).
            if (proveedor.getZonaActual() == null || !zonaId.equals(proveedor.getZonaActual().getId())) {
                throw new ValidacionNegocioException(
                        "El proveedor " + proveedor.getNombre() + " no pertenece actualmente a esta zona");
            }

            RutaProveedorOrden ruta = new RutaProveedorOrden();
            ruta.setZona(zona);
            ruta.setProveedor(proveedor);
            ruta.setOrden(item.orden());
            ruta.setHoraEstimada(item.horaEstimada());
            nuevos.add(ruta);
        }

        // Borrar + flush antes de insertar: evita chocar transitoriamente con
        // los UNIQUE(zona_id, proveedor_id) / UNIQUE(zona_id, orden) al redefinir.
        repository.deleteByZonaId(zonaId);
        repository.flush();

        List<RutaProveedorOrden> guardados = repository.saveAll(nuevos);
        return guardados.stream().map(this::toResponse).toList();
    }

    public List<RutaProveedorOrdenResponse> listarPorZona(UUID zonaId) {
        if (!zonaRepository.existsById(zonaId)) {
            throw new RecursoNoEncontradoException("Zona no encontrada: " + zonaId);
        }
        return repository.findByZonaIdOrderByOrdenAsc(zonaId).stream().map(this::toResponse).toList();
    }

    private RutaProveedorOrdenResponse toResponse(RutaProveedorOrden r) {
        return new RutaProveedorOrdenResponse(
                r.getId(), r.getProveedor().getId(), r.getProveedor().getNombre(), r.getOrden(), r.getHoraEstimada());
    }
}
