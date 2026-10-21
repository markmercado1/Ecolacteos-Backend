package com.planta.acopio.service;

import com.planta.acopio.dto.InsumoRequest;
import com.planta.acopio.dto.InsumoResponse;
import com.planta.acopio.entity.Insumo;
import com.planta.acopio.exception.RecursoNoEncontradoException;
import com.planta.acopio.exception.ValidacionNegocioException;
import com.planta.acopio.repository.InsumoRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

// Catalogo administrable, mismo patron/shape que TipoQuesoService (crear/listarActivos/obtener/actualizar/
// desactivar/reactivar).
//
// V19 (kardex): el stock ya no se edita desde el catalogo. Al crear, el stock indicado es el SALDO_INICIAL
// del kardex; despues solo cambia por ingresos, salidas de produccion o ajustes por conteo
// (InventarioInsumoService), cada uno con su movimiento trazable.
@Service
public class InsumoService {

    private final InsumoRepository repository;
    private final InventarioInsumoService inventarioInsumoService;

    public InsumoService(InsumoRepository repository, InventarioInsumoService inventarioInsumoService) {
        this.repository = repository;
        this.inventarioInsumoService = inventarioInsumoService;
    }

    @Transactional
    public InsumoResponse crear(InsumoRequest dto) {
        Insumo i = new Insumo();
        i.setNombre(dto.nombre());
        i.setUnidadMedida(dto.unidadMedida());
        i.setStock(dto.stock() != null ? dto.stock() : BigDecimal.ZERO);
        Insumo guardado = repository.save(i);
        inventarioInsumoService.registrarSaldoInicial(guardado, guardado.getStock());
        return toResponse(guardado);
    }

    public List<InsumoResponse> listarActivos() {
        return repository.findByActivoTrue().stream().map(this::toResponse).toList();
    }

    public InsumoResponse obtener(UUID id) {
        return toResponse(repository.findById(id)
                .orElseThrow(() -> new RecursoNoEncontradoException("Insumo no encontrado: " + id)));
    }

    // Edita nombre y unidad. `stock` se tolera solo si coincide con el actual (clientes que reenvian el valor
    // leido); un valor distinto se rechaza: cambiar el stock sin movimiento romperia el kardex.
    @Transactional
    public InsumoResponse actualizar(UUID id, InsumoRequest dto) {
        Insumo i = repository.findById(id)
                .orElseThrow(() -> new RecursoNoEncontradoException("Insumo no encontrado: " + id));
        if (dto.stock() != null && dto.stock().compareTo(i.getStock()) != 0) {
            throw new ValidacionNegocioException("El stock de un insumo no se edita desde el catálogo: registra un "
                    + "ingreso o un ajuste por conteo físico en Inventario para que quede en el kardex.");
        }
        i.setNombre(dto.nombre());
        i.setUnidadMedida(dto.unidadMedida());
        return toResponse(i);
    }

    @Transactional
    public void desactivar(UUID id) {
        Insumo i = repository.findById(id)
                .orElseThrow(() -> new RecursoNoEncontradoException("Insumo no encontrado: " + id));
        i.setActivo(false);
    }

    @Transactional
    public InsumoResponse reactivar(UUID id) {
        Insumo i = repository.findById(id)
                .orElseThrow(() -> new RecursoNoEncontradoException("Insumo no encontrado: " + id));
        i.setActivo(true);
        return toResponse(i);
    }

    private InsumoResponse toResponse(Insumo i) {
        return new InsumoResponse(i.getId(), i.getNombre(), i.getUnidadMedida(), i.getActivo(), i.getStock());
    }
}
