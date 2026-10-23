package com.planta.acopio.service;

import com.planta.acopio.dto.CorreccionRegistroRequest;
import com.planta.acopio.dto.CorreccionRegistroResponse;
import com.planta.acopio.entity.CorreccionRegistro;
import com.planta.acopio.entity.RegistroAcopio;
import com.planta.acopio.entity.Usuario;
import com.planta.acopio.exception.RecursoNoEncontradoException;
import com.planta.acopio.exception.ValidacionNegocioException;
import com.planta.acopio.repository.CorreccionRegistroRepository;
import com.planta.acopio.repository.RegistroAcopioRepository;
import com.planta.acopio.repository.UsuarioRepository;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;

// RNF-03/05: RegistroAcopio nunca se edita. Este service SOLO anexa
// correcciones con trazabilidad (usuario + motivo + fecha) -- nunca hay un
// metodo update()/delete() aqui ni en RegistroAcopioService.
@Service
public class CorreccionRegistroService {

    private final CorreccionRegistroRepository repository;
    private final RegistroAcopioRepository registroAcopioRepository;
    private final UsuarioRepository usuarioRepository;
    private final AjustePagoService ajustePagoService;
    private final LitrosEfectivos litrosEfectivos;

    public CorreccionRegistroService(CorreccionRegistroRepository repository,
                                      RegistroAcopioRepository registroAcopioRepository,
                                      UsuarioRepository usuarioRepository,
                                      AjustePagoService ajustePagoService,
                                      LitrosEfectivos litrosEfectivos) {
        this.repository = repository;
        this.registroAcopioRepository = registroAcopioRepository;
        this.usuarioRepository = usuarioRepository;
        this.ajustePagoService = ajustePagoService;
        this.litrosEfectivos = litrosEfectivos;
    }

    // Fase B: la correccion exige una justificacion registrada (texto libre) y un cambio real respecto a la cantidad vigente
    // (la ultima correccion valida o el original). Se bloquea la fila del registro: dos correcciones simultaneas se aplican una
    // tras otra y cada ajuste parte de los litros ya contabilizados. Los litros originales del registro no se tocan.
    @Transactional
    public CorreccionRegistroResponse anexar(UUID registroAcopioId, CorreccionRegistroRequest dto) {
        String motivo = dto.motivo() == null ? "" : dto.motivo().trim();
        if (motivo.isEmpty()) {
            throw new ValidacionNegocioException("El motivo de la corrección es obligatorio");
        }

        RegistroAcopio registro = registroAcopioRepository.findByIdParaActualizar(registroAcopioId)
                .orElseThrow(() -> new RecursoNoEncontradoException("Registro de acopio no encontrado: " + registroAcopioId));

        BigDecimal litrosCorregido = dto.litrosCorregido().setScale(2, RoundingMode.HALF_UP); // la columna es NUMERIC(8,2)
        BigDecimal vigente = litrosEfectivos.deRegistro(registro).litros();
        if (litrosCorregido.compareTo(vigente) == 0) {
            throw new ValidacionNegocioException("La cantidad corregida (" + litrosCorregido.toPlainString()
                    + " L) es igual a la vigente; no hay nada que corregir");
        }

        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        Usuario usuario = usuarioRepository.findByEmail(email)
                .orElseThrow(() -> new RecursoNoEncontradoException("Usuario autenticado no encontrado: " + email));

        CorreccionRegistro correccion = new CorreccionRegistro();
        correccion.setRegistroAcopio(registro);
        correccion.setUsuario(usuario);
        correccion.setLitrosAnterior(registro.getLitros()); // el "anterior" es siempre el original inmutable
        correccion.setLitrosCorregido(litrosCorregido);
        correccion.setMotivo(motivo);

        correccion = repository.save(correccion);

        // Fase 3, decision de negocio 1 (opcion C, confirmada): si el registro
        // ya estaba liquidado, esto genera un AjustePago -- no bloquea la
        // correccion (RegistroAcopio/Pago siguen inmutables, no se tocan aqui).
        // Si no estaba liquidado, generarSiCorrespondeAjuste no hace nada.
        ajustePagoService.generarSiCorrespondeAjuste(registro, correccion);

        return new CorreccionRegistroResponse(
                correccion.getId(), registro.getId(), correccion.getLitrosAnterior(),
                correccion.getLitrosCorregido(), correccion.getMotivo(), usuario.getNombre(), correccion.getCreadoEn());
    }

    public List<CorreccionRegistroResponse> listarPorRegistro(UUID registroAcopioId) {
        return repository.findByRegistroAcopioIdOrderByCreadoEnDesc(registroAcopioId).stream()
                .map(c -> new CorreccionRegistroResponse(
                        c.getId(), c.getRegistroAcopio().getId(), c.getLitrosAnterior(),
                        c.getLitrosCorregido(), c.getMotivo(), c.getUsuario().getNombre(), c.getCreadoEn()))
                .toList();
    }
}
