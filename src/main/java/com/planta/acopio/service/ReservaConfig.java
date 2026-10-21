package com.planta.acopio.service;

import com.planta.acopio.entity.ConfiguracionSistema;
import com.planta.acopio.repository.ConfiguracionSistemaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

// Fase 4: lee los parametros de las reservas de configuracion_sistema (la misma tabla que edita ADMIN en Catalogos ->
// Configuracion). Se lee en cada uso, de modo que un cambio rige para las NUEVAS reservas de inmediato (las ya creadas
// conservan su fecha maxima de retiro y su expiracion, que se calcularon al crearse).
@Component
public class ReservaConfig {

    private static final Logger log = LoggerFactory.getLogger(ReservaConfig.class);

    private final ConfiguracionSistemaRepository repository;

    public ReservaConfig(ConfiguracionSistemaRepository repository) {
        this.repository = repository;
    }

    public ReservaParametros actuales() {
        Map<String, String> valores = new HashMap<>();
        for (ConfiguracionSistema c : repository.findAll()) valores.put(c.getClave(), c.getValor());
        return ReservaParametros.desde(valores, log::warn);
    }
}
