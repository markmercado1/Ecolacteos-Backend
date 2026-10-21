package com.planta.acopio.service;

import com.planta.acopio.dto.ConfiguracionSistemaResponse;
import com.planta.acopio.dto.ConfiguracionSistemaUpdateRequest;
import com.planta.acopio.entity.ConfiguracionSistema;
import com.planta.acopio.exception.RecursoNoEncontradoException;
import com.planta.acopio.repository.ConfiguracionSistemaRepository;
import com.planta.acopio.repository.UsuarioRepository;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class ConfiguracionSistemaService {

    private final ConfiguracionSistemaRepository repository;
    private final UsuarioRepository usuarioRepository;

    public ConfiguracionSistemaService(ConfiguracionSistemaRepository repository, UsuarioRepository usuarioRepository) {
        this.repository = repository;
        this.usuarioRepository = usuarioRepository;
    }

    public List<ConfiguracionSistemaResponse> listar() {
        return repository.findAll().stream()
                .map(c -> new ConfiguracionSistemaResponse(c.getClave(), c.getValor(), c.getDescripcion()))
                .toList();
    }

    public String valorDe(String clave) {
        return repository.findById(clave)
                .orElseThrow(() -> new RecursoNoEncontradoException("Configuracion no encontrada: " + clave))
                .getValor();
    }

    // Fase 4: las claves RESERVA_* se validan al guardar (rango, formato de dias/hora/feriados) para que un valor mal
    // escrito nunca llegue a las reservas, y todo cambio queda con quien lo hizo y cuando (actualizado_por/actualizado_en).
    @Transactional
    public ConfiguracionSistemaResponse actualizar(String clave, ConfiguracionSistemaUpdateRequest dto) {
        ConfiguracionSistema c = repository.findById(clave)
                .orElseThrow(() -> new RecursoNoEncontradoException("Configuracion no encontrada: " + clave));
        ReservaParametros.validar(clave, dto.valor());
        c.setValor(dto.valor());
        c.setActualizadoEn(LocalDateTime.now());
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getName() != null) {
            usuarioRepository.findByEmail(auth.getName()).ifPresent(u -> c.setActualizadoPor(u.getId()));
        }
        return new ConfiguracionSistemaResponse(c.getClave(), c.getValor(), c.getDescripcion());
    }
}
