package com.planta.acopio.repository;

import com.planta.acopio.entity.ClienteTipoPrecio;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface ClienteTipoPrecioRepository extends JpaRepository<ClienteTipoPrecio, ClienteTipoPrecio.Clave> {

    boolean existsByClienteIdAndTipoCodigo(UUID clienteId, String tipoCodigo);

    List<ClienteTipoPrecio> findByClienteId(UUID clienteId);

    List<ClienteTipoPrecio> findByClienteIdIn(Collection<UUID> clienteIds);
}
