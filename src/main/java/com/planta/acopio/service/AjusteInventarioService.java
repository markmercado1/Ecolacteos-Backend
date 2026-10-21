package com.planta.acopio.service;

import com.planta.acopio.dto.AjusteInventarioRequest;
import com.planta.acopio.dto.AjusteInventarioResponse;
import com.planta.acopio.entity.AjusteInventario;
import com.planta.acopio.entity.Presentacion;
import com.planta.acopio.entity.Usuario;
import com.planta.acopio.exception.RecursoNoEncontradoException;
import com.planta.acopio.exception.ValidacionNegocioException;
import com.planta.acopio.repository.AjusteInventarioRepository;
import com.planta.acopio.repository.MovimientoInventarioRepository;
import com.planta.acopio.repository.PresentacionRepository;
import com.planta.acopio.repository.UsuarioRepository;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

// Inventario: accion "Ajustar stock" (V14__ajuste_inventario.sql), para diferencias encontradas al contar
// fisicamente (perdidas, roturas, errores de conteo). Nunca devuelve ni consume leche, nunca reescribe como
// se produjo -- solo agrega un movimiento nuevo al libro mayor de la presentacion, con motivo/usuario/fecha
// (append-only, mismo criterio que CorreccionRegistroService: sin metodos update()/delete() aqui).
@Service
public class AjusteInventarioService {

    private final AjusteInventarioRepository repository;
    private final PresentacionRepository presentacionRepository;
    private final MovimientoInventarioRepository movimientoRepository;
    private final UsuarioRepository usuarioRepository;
    private final InventarioService inventarioService;

    public AjusteInventarioService(AjusteInventarioRepository repository, PresentacionRepository presentacionRepository,
                                    MovimientoInventarioRepository movimientoRepository, UsuarioRepository usuarioRepository,
                                    InventarioService inventarioService) {
        this.repository = repository;
        this.presentacionRepository = presentacionRepository;
        this.movimientoRepository = movimientoRepository;
        this.usuarioRepository = usuarioRepository;
        this.inventarioService = inventarioService;
    }

    // Mismo bloqueo pesimista que validarStockSuficiente (SELECT ... FOR UPDATE sobre la presentacion):
    // el "stock del sistema" que se compara contra el conteo fisico se lee bajo ese lock, para que una
    // venta/produccion concurrente de la MISMA presentacion nunca quede pisada por este ajuste (y viceversa).
    @Transactional
    public AjusteInventarioResponse crear(AjusteInventarioRequest dto) {
        Presentacion presentacion = presentacionRepository.buscarConBloqueoParaActualizarStock(dto.presentacionId())
                .orElseThrow(() -> new RecursoNoEncontradoException("Presentación no encontrada: " + dto.presentacionId()));

        int cantidadAnterior = (int) movimientoRepository.sumarCantidadPorPresentacion(presentacion.getId());
        int delta = dto.cantidadFisica() - cantidadAnterior;
        if (delta == 0) {
            throw new ValidacionNegocioException(
                    "El conteo físico coincide con el stock del sistema (" + cantidadAnterior
                            + "); no hay ninguna diferencia que ajustar.");
        }

        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        Usuario usuario = usuarioRepository.findByEmail(email)
                .orElseThrow(() -> new RecursoNoEncontradoException("Usuario autenticado no encontrado: " + email));

        AjusteInventario ajuste = new AjusteInventario();
        ajuste.setPresentacion(presentacion);
        ajuste.setUsuario(usuario);
        ajuste.setCantidadAnterior(cantidadAnterior);
        ajuste.setCantidadCorregida(dto.cantidadFisica());
        ajuste.setMotivo(dto.motivo());
        ajuste = repository.save(ajuste);

        // El efecto real sobre el stock: un movimiento mas del mismo libro mayor, en la MISMA transaccion
        // -- no puede existir un AjusteInventario sin su movimiento compensatorio.
        inventarioService.registrarAjuste(ajuste, delta);

        return toResponse(ajuste, delta);
    }

    @Transactional(readOnly = true)
    public Page<AjusteInventarioResponse> listarPorPresentacion(UUID presentacionId, Pageable pageable) {
        return repository.findByPresentacionIdOrderByCreadoEnDescIdDesc(presentacionId, pageable)
                .map(a -> toResponse(a, a.getCantidadCorregida() - a.getCantidadAnterior()));
    }

    private AjusteInventarioResponse toResponse(AjusteInventario a, int delta) {
        return new AjusteInventarioResponse(
                a.getId(), a.getPresentacion().getId(), a.getPresentacion().getNombre(),
                a.getPresentacion().getProducto().getNombre(),
                a.getCantidadAnterior(), a.getCantidadCorregida(), delta,
                a.getMotivo(), a.getUsuario().getNombre(), a.getCreadoEn());
    }
}
