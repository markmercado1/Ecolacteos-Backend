package com.planta.acopio.service;

import com.planta.acopio.dto.PuntoGpsJornadaDTO;
import com.planta.acopio.entity.Jornada;
import com.planta.acopio.entity.PuntoGpsJornada;
import com.planta.acopio.entity.Usuario;
import com.planta.acopio.exception.RecursoNoEncontradoException;
import com.planta.acopio.exception.ValidacionNegocioException;
import com.planta.acopio.repository.JornadaRepository;
import com.planta.acopio.repository.PuntoGpsJornadaRepository;
import com.planta.acopio.repository.UsuarioRepository;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Fase 3 -- Recorrido GPS (docs/ESPEC_JORNADA_TRAZABILIDAD.md, Ecolacteos-Frontend). Idempotente por
// uuidCliente (mismo criterio que JornadaService.crear/RegistroAcopioService): reenviar el mismo punto
// nunca lo duplica, devuelve el ya existente sin tocarlo. Aislamiento por usuario (ESPEC §19): la jornada
// indicada debe pertenecer al acopiador autenticado -- nunca se confia en un usuarioId enviado por el
// movil, la autoridad es el JWT.
@Service
public class PuntoGpsJornadaService {

    private final PuntoGpsJornadaRepository puntoGpsJornadaRepository;
    private final JornadaRepository jornadaRepository;
    private final UsuarioRepository usuarioRepository;

    public PuntoGpsJornadaService(PuntoGpsJornadaRepository puntoGpsJornadaRepository,
                                   JornadaRepository jornadaRepository, UsuarioRepository usuarioRepository) {
        this.puntoGpsJornadaRepository = puntoGpsJornadaRepository;
        this.jornadaRepository = jornadaRepository;
        this.usuarioRepository = usuarioRepository;
    }

    @Transactional
    public PuntoGpsJornada registrarOIgnorarSiDuplicado(PuntoGpsJornadaDTO dto) {
        var existente = puntoGpsJornadaRepository.findByUuidCliente(dto.uuidCliente());
        if (existente.isPresent()) {
            return existente.get();
        }

        Usuario acopiador = usuarioActual();
        Jornada jornada = jornadaRepository.findById(dto.jornadaId())
                .orElseThrow(() -> new RecursoNoEncontradoException("Jornada no encontrada: " + dto.jornadaId()));
        if (!jornada.getAcopiador().getId().equals(acopiador.getId())) {
            throw new ValidacionNegocioException("La jornada indicada no pertenece al acopiador autenticado");
        }

        PuntoGpsJornada punto = new PuntoGpsJornada();
        punto.setUuidCliente(dto.uuidCliente());
        punto.setJornada(jornada);
        punto.setLat(dto.lat());
        punto.setLng(dto.lng());
        punto.setPrecisionM(dto.precisionM());
        punto.setCapturadoEn(dto.capturadoEn());

        return puntoGpsJornadaRepository.save(punto);
    }

    private Usuario usuarioActual() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String email = auth.getName();
        return usuarioRepository.findByEmail(email)
                .orElseThrow(() -> new RecursoNoEncontradoException("Usuario autenticado no encontrado: " + email));
    }
}
