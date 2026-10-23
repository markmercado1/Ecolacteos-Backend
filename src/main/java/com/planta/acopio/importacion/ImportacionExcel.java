package com.planta.acopio.importacion;

import com.planta.acopio.entity.Usuario;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Una importacion desde Excel: la vista previa (filas ya validadas por el servidor) y, si se confirma, su resultado. Ver
 * V26__importacion_excel.sql. Las filas viajan en JSON porque la confirmacion crea EXACTAMENTE lo que se vio en la vista
 * previa, sin volver a leer el archivo ni aceptar datos del cliente.
 */
@Entity
@Table(name = "importacion_excel")
public class ImportacionExcel {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false, length = 40)
    private String tipo;

    @Column(nullable = false)
    private Integer version;

    @ManyToOne(optional = false)
    @JoinColumn(name = "usuario_id")
    private Usuario usuario;

    @Column(name = "nombre_archivo", nullable = false, length = 200)
    private String nombreArchivo;

    @Column(name = "hash_archivo", nullable = false, length = 64)
    private String hashArchivo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EstadoImportacion estado = EstadoImportacion.PREVISUALIZADA;

    @Column(name = "total_filas", nullable = false)
    private Integer totalFilas;

    @Column(nullable = false)
    private Integer validas;

    @Column(nullable = false)
    private Integer invalidas;

    @Column(nullable = false)
    private Integer duplicadas;

    @Column(nullable = false)
    private Integer omitidas;

    @Column(nullable = false)
    private Integer creadas = 0;

    @Column(name = "filas_json", nullable = false, columnDefinition = "TEXT")
    private String filasJson;

    @Column(name = "creado_en", nullable = false, updatable = false)
    private LocalDateTime creadoEn = LocalDateTime.now();

    @Column(name = "expira_en", nullable = false)
    private LocalDateTime expiraEn;

    @Column(name = "confirmado_en")
    private LocalDateTime confirmadoEn;

    public ImportacionExcel() {}

    public UUID getId() { return id; }
    public String getTipo() { return tipo; }
    public void setTipo(String tipo) { this.tipo = tipo; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
    public Usuario getUsuario() { return usuario; }
    public void setUsuario(Usuario usuario) { this.usuario = usuario; }
    public String getNombreArchivo() { return nombreArchivo; }
    public void setNombreArchivo(String nombreArchivo) { this.nombreArchivo = nombreArchivo; }
    public String getHashArchivo() { return hashArchivo; }
    public void setHashArchivo(String hashArchivo) { this.hashArchivo = hashArchivo; }
    public EstadoImportacion getEstado() { return estado; }
    public void setEstado(EstadoImportacion estado) { this.estado = estado; }
    public Integer getTotalFilas() { return totalFilas; }
    public void setTotalFilas(Integer totalFilas) { this.totalFilas = totalFilas; }
    public Integer getValidas() { return validas; }
    public void setValidas(Integer validas) { this.validas = validas; }
    public Integer getInvalidas() { return invalidas; }
    public void setInvalidas(Integer invalidas) { this.invalidas = invalidas; }
    public Integer getDuplicadas() { return duplicadas; }
    public void setDuplicadas(Integer duplicadas) { this.duplicadas = duplicadas; }
    public Integer getOmitidas() { return omitidas; }
    public void setOmitidas(Integer omitidas) { this.omitidas = omitidas; }
    public Integer getCreadas() { return creadas; }
    public void setCreadas(Integer creadas) { this.creadas = creadas; }
    public String getFilasJson() { return filasJson; }
    public void setFilasJson(String filasJson) { this.filasJson = filasJson; }
    public LocalDateTime getCreadoEn() { return creadoEn; }
    public LocalDateTime getExpiraEn() { return expiraEn; }
    public void setExpiraEn(LocalDateTime expiraEn) { this.expiraEn = expiraEn; }
    public LocalDateTime getConfirmadoEn() { return confirmadoEn; }
    public void setConfirmadoEn(LocalDateTime confirmadoEn) { this.confirmadoEn = confirmadoEn; }
}
