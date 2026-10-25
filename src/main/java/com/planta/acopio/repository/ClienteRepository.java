package com.planta.acopio.repository;

import com.planta.acopio.entity.Cliente;
import com.planta.acopio.service.BusquedaTexto;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ClienteRepository extends JpaRepository<Cliente, UUID> {

    @Query("SELECT c FROM Cliente c WHERE c.dni = :dni AND c.fusionadoEnId IS NULL")
    Optional<Cliente> findVigenteByDni(@Param("dni") String dni);

    @Query("SELECT c FROM Cliente c WHERE c.ruc = :ruc AND c.fusionadoEnId IS NULL")
    Optional<Cliente> findVigenteByRuc(@Param("ruc") String ruc);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM Cliente c WHERE c.id = :id")
    Optional<Cliente> bloquear(@Param("id") UUID id);

    /** Fichas no fusionadas con alguno de esos telefonos (para sugerir coincidencias, nunca para unir solas). */
    @Query("SELECT DISTINCT c FROM Cliente c, ClienteTelefono t WHERE t.clienteId = c.id AND t.telefono = :telefono AND c.fusionadoEnId IS NULL")
    List<Cliente> conTelefono(@Param("telefono") String telefono);

    // Padron paginado (ADMIN y VENTAS): busqueda por nombre, razon social, DNI, RUC o telefono (sin acentos ni mayusculas); filtros
    // activo, mayorista y con autorizacion comercial vigente. Las fusionadas no se listan. Mas reciente primero.
    String FILTROS =
            "FROM cliente c WHERE c.fusionado_en_id IS NULL "
            + "AND (CAST(:activo AS boolean) IS NULL OR c.activo = CAST(:activo AS boolean)) "
            + "AND (CAST(:mayorista AS boolean) IS NULL OR c.mayorista = CAST(:mayorista AS boolean)) "
            + "AND (CAST(:conComercial AS boolean) IS NULL OR CAST(:conComercial AS boolean) = (COALESCE((SELECT cc.estado FROM cliente_consentimiento cc "
            + "     WHERE cc.cliente_id = c.id AND cc.finalidad = 'COMUNICACIONES_COMERCIALES' ORDER BY cc.registrado_en DESC, cc.id DESC LIMIT 1), '') = 'OTORGADO')) "
            + "AND (" + BusquedaTexto.SIN_TERMINO + " OR "
            + BusquedaTexto.PLEGAR_ABRE + "c.nombre" + BusquedaTexto.PLEGAR_CIERRA + BusquedaTexto.COINCIDE + " OR "
            + BusquedaTexto.PLEGAR_ABRE + "coalesce(c.razon_social, '')" + BusquedaTexto.PLEGAR_CIERRA + BusquedaTexto.COINCIDE + " OR "
            + BusquedaTexto.PLEGAR_ABRE + "coalesce(c.dni, '')" + BusquedaTexto.PLEGAR_CIERRA + BusquedaTexto.COINCIDE + " OR "
            + BusquedaTexto.PLEGAR_ABRE + "coalesce(c.ruc, '')" + BusquedaTexto.PLEGAR_CIERRA + BusquedaTexto.COINCIDE + " OR "
            + "EXISTS (SELECT 1 FROM cliente_telefono t WHERE t.cliente_id = c.id AND t.telefono LIKE CAST(:telefono AS varchar))) ";

    @Query(value = "SELECT c.* " + FILTROS + "ORDER BY c.creado_en DESC, c.id DESC",
           countQuery = "SELECT count(*) " + FILTROS, nativeQuery = true)
    Page<Cliente> buscar(@Param("activo") Boolean activo, @Param("mayorista") Boolean mayorista, @Param("conComercial") Boolean conComercial,
                         @Param("busqueda") String busqueda, @Param("telefono") String telefono, Pageable pageable);
}
