package com.planta.acopio.importacion;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

public interface ImportacionExcelRepository extends JpaRepository<ImportacionExcel, UUID> {

    /**
     * Toma la importacion con bloqueo de escritura (SELECT ... FOR UPDATE). Dos confirmaciones simultaneas (doble clic, reintento
     * de red, dos pestanas) se serializan aqui: la segunda espera, ve CONFIRMADA y devuelve el resultado ya guardado en vez de
     * crear los registros otra vez.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from ImportacionExcel i where i.id = :id")
    Optional<ImportacionExcel> bloquear(@Param("id") UUID id);

    /** Primer importacion CONFIRMADA del mismo tipo y contenido (para no cargar dos veces el mismo archivo). */
    Optional<ImportacionExcel> findFirstByTipoAndHashArchivoAndEstadoOrderByConfirmadoEnAsc(String tipo, String hashArchivo, EstadoImportacion estado);

    /** Limpieza: vistas previas que nadie confirmo. Las CONFIRMADA se conservan como bitacora. */
    @Modifying
    @Query("delete from ImportacionExcel i where i.estado <> com.planta.acopio.importacion.EstadoImportacion.CONFIRMADA and i.expiraEn < :limite")
    int borrarNoConfirmadasVencidas(@Param("limite") LocalDateTime limite);
}
