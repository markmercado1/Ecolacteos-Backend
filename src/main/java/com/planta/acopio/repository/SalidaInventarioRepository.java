package com.planta.acopio.repository;
import com.planta.acopio.entity.SalidaInventario;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;
public interface SalidaInventarioRepository extends JpaRepository<SalidaInventario, UUID> {
    Optional<SalidaInventario> findByUuidCliente(UUID uuidCliente);
}
