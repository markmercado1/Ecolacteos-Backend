package com.planta.acopio.repository;

import com.planta.acopio.entity.ClienteTelefono;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface ClienteTelefonoRepository extends JpaRepository<ClienteTelefono, UUID> {
    List<ClienteTelefono> findByClienteIdOrderByCreadoEnAsc(UUID clienteId);
    List<ClienteTelefono> findByClienteIdIn(Collection<UUID> clienteIds);
    boolean existsByClienteIdAndTelefono(UUID clienteId, String telefono);
}
