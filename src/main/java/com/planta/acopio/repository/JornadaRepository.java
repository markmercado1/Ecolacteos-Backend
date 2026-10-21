package com.planta.acopio.repository;

import com.planta.acopio.entity.EstadoJornada;
import com.planta.acopio.entity.Jornada;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Optional;
import java.util.UUID;

public interface JornadaRepository extends JpaRepository<Jornada, UUID> {

    Optional<Jornada> findByUuidCliente(String uuidCliente);

    // Verificacion previa (mensaje 409 legible en el caso normal, sin carrera) -- el cerrojo real contra
    // condiciones de carrera entre dos dispositivos es el indice parcial unico de V3__jornada.sql. Devuelve
    // la entidad (no solo un boolean) para que el mensaje de conflicto pueda citar horaInicio/estado --
    // ver JornadaService.verificarSinJornadaActivaConflictiva.
    @Query("SELECT j FROM Jornada j WHERE j.acopiador.id = :acopiadorId " +
           "AND j.estado IN (com.planta.acopio.entity.EstadoJornada.EN_RECOLECCION, com.planta.acopio.entity.EstadoJornada.REGRESANDO_A_PLANTA)")
    Optional<Jornada> findActivaPorAcopiador(@Param("acopiadorId") UUID acopiadorId);

    @Query("SELECT COUNT(j) > 0 FROM Jornada j WHERE j.unidad.id = :unidadId " +
           "AND j.estado IN (com.planta.acopio.entity.EstadoJornada.EN_RECOLECCION, com.planta.acopio.entity.EstadoJornada.REGRESANDO_A_PLANTA)")
    boolean existeActivaParaUnidad(@Param("unidadId") UUID unidadId);

    // Fase 2 (docs/ESPEC_JORNADA_TRAZABILIDAD.md): bandeja de RECEPCION -- jornadas listas para recibir,
    // mas antigua primero (orden de llegada real, FIFO de atencion).
    java.util.List<Jornada> findByEstadoOrderByHoraInicioAsc(EstadoJornada estado);

    // Soporte/administracion (ver AdminJornadaController): TODAS las jornadas activas, EN_RECOLECCION
    // incluida -- a diferencia de findByEstadoOrderByHoraInicioAsc(REGRESANDO_A_PLANTA), que es lo unico
    // que ve la bandeja normal de RECEPCION. Una jornada huerfana que nunca se finalizo (se quedo
    // EN_RECOLECCION) bloquea al acopiador con el 409 "ya existe una jornada activa" sin que RECEPCION
    // pueda verla ni recibirla jamas -- este metodo es la unica forma de encontrarla.
    @Query("SELECT j FROM Jornada j WHERE j.estado IN (com.planta.acopio.entity.EstadoJornada.EN_RECOLECCION, com.planta.acopio.entity.EstadoJornada.REGRESANDO_A_PLANTA) " +
           "ORDER BY j.horaInicio ASC")
    java.util.List<Jornada> findAllActivas();
}
