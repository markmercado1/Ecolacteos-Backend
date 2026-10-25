package com.planta.acopio.repository;

import com.planta.acopio.entity.ClienteConsentimiento;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ClienteConsentimientoRepository extends JpaRepository<ClienteConsentimiento, UUID> {
    List<ClienteConsentimiento> findByClienteIdOrderByRegistradoEnDescIdDesc(UUID clienteId);
    Optional<ClienteConsentimiento> findFirstByClienteIdAndFinalidadOrderByRegistradoEnDescIdDesc(UUID clienteId, String finalidad);
}
