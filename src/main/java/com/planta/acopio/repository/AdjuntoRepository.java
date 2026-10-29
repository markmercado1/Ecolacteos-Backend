package com.planta.acopio.repository;

import com.planta.acopio.entity.Adjunto;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AdjuntoRepository extends JpaRepository<Adjunto, UUID> {
    List<Adjunto> findByEntidadTipoAndEntidadIdOrderBySubidoEnAscIdAsc(String entidadTipo, UUID entidadId);

    boolean existsByEntidadTipoAndEntidadIdAndSha256(String entidadTipo, UUID entidadId, String sha256);

    long countByEntidadTipoAndEntidadId(String entidadTipo, UUID entidadId);
}
