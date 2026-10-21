package com.planta.acopio.repository;

import com.planta.acopio.entity.PuntoGpsJornada;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PuntoGpsJornadaRepository extends JpaRepository<PuntoGpsJornada, UUID> {

    Optional<PuntoGpsJornada> findByUuidCliente(String uuidCliente);

    // Fase 3: orden cronologico -- unico consumidor previsto es la futura web (ESPEC §17); el movil calcula
    // distancia/paradas localmente a partir de sus propios puntos, no de esta consulta.
    List<PuntoGpsJornada> findByJornadaIdOrderByCapturadoEnAsc(UUID jornadaId);

    long countByJornadaId(UUID jornadaId);
}
