package com.planta.acopio.service;

import com.planta.acopio.dto.PrecioLitroRequest;
import com.planta.acopio.dto.PrecioLitroResponse;
import com.planta.acopio.entity.PrecioLitro;
import com.planta.acopio.exception.ConflictoNegocioException;
import com.planta.acopio.repository.PrecioLitroRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

// Auditoria CRUD (prioridad ALTA, bloqueante): antes no existia ningun
// service/controller para precio_litro, pese a que PagoService,
// ForecastingService y PanelEconomicoService ya dependian de
// PrecioLitroRepository.findVigenteHoy() -- sin este service era imposible
// establecer el precio vigente desde la API (solo via INSERT manual en BD).
//
// Es una serie historica (igual que TipoQueso/Zona, pero append-only): un
// precio nuevo no reemplaza al anterior, solo abre una vigencia distinta.
// Por eso no hay actualizar()/desactivar() -- corregir un precio mal cargado
// se hace creando uno nuevo con la fecha correcta, nunca reescribiendo el
// historico (mismo principio de trazabilidad que RegistroAcopio).
@Service
public class PrecioLitroService {

    private final PrecioLitroRepository repository;

    public PrecioLitroService(PrecioLitroRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public PrecioLitroResponse crear(PrecioLitroRequest dto) {
        if (repository.existsByVigenteDesde(dto.vigenteDesde())) {
            throw new ConflictoNegocioException(
                    "Ya existe un precio por litro con vigencia desde " + dto.vigenteDesde());
        }

        PrecioLitro precio = new PrecioLitro();
        precio.setPrecio(dto.precio());
        precio.setVigenteDesde(dto.vigenteDesde());

        return toResponse(repository.save(precio));
    }

    // Paginacion pendiente: el historico se pagina en la BD. Como la web ya no
    // recibe la serie completa, el precio vigente se expone aparte (vigente())
    // en vez de deducirlo de la pagina visible.
    public Page<PrecioLitroResponse> listar(Pageable pageable) {
        return repository.findAllByOrderByVigenteDesdeDescIdDesc(pageable).map(this::toResponse);
    }

    // Misma regla que usan PagoService/PanelEconomicoService: vigenteDesde mas
    // reciente que ya llego (<= hoy). Vacio si todavia no hay ninguno vigente.
    public Optional<PrecioLitroResponse> vigente() {
        return repository.findVigenteHoy().map(this::toResponse);
    }

    private PrecioLitroResponse toResponse(PrecioLitro p) {
        return new PrecioLitroResponse(p.getId(), p.getPrecio(), p.getVigenteDesde());
    }
}
