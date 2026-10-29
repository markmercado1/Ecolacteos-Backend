package com.planta.acopio.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;

// V46: referencia a un archivo adjunto (foto o PDF de un recibo). El archivo esta en disco, fuera de la BD. Generico: hoy solo
// DOCUMENTO_INGRESO. texto_extraido: reservado para OCR a futuro, siempre nulo por ahora.
@Entity
@Table(name = "adjunto")
public class Adjunto {

    public static final String DOCUMENTO_INGRESO = "DOCUMENTO_INGRESO";

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "entidad_tipo", nullable = false, length = 30)
    private String entidadTipo;

    @Column(name = "entidad_id", nullable = false)
    private UUID entidadId;

    @Column(name = "nombre_original", nullable = false, length = 200)
    private String nombreOriginal;

    @Column(name = "content_type", nullable = false, length = 60)
    private String contentType;

    @Column(name = "tamano_bytes", nullable = false)
    private Long tamanoBytes;

    @Column(name = "ruta_almacenamiento", nullable = false, unique = true, length = 300)
    private String rutaAlmacenamiento;

    @Column(nullable = false, length = 64)
    private String sha256;

    @Column(name = "texto_extraido", columnDefinition = "TEXT")
    private String textoExtraido;

    @ManyToOne(optional = false)
    @JoinColumn(name = "subido_por")
    private Usuario subidoPor;

    @Column(name = "subido_en", nullable = false, updatable = false)
    private LocalDateTime subidoEn = LocalDateTime.now();

    public Adjunto() {}

    public UUID getId() { return id; }
    public String getEntidadTipo() { return entidadTipo; }
    public void setEntidadTipo(String entidadTipo) { this.entidadTipo = entidadTipo; }
    public UUID getEntidadId() { return entidadId; }
    public void setEntidadId(UUID entidadId) { this.entidadId = entidadId; }
    public String getNombreOriginal() { return nombreOriginal; }
    public void setNombreOriginal(String nombreOriginal) { this.nombreOriginal = nombreOriginal; }
    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }
    public Long getTamanoBytes() { return tamanoBytes; }
    public void setTamanoBytes(Long tamanoBytes) { this.tamanoBytes = tamanoBytes; }
    public String getRutaAlmacenamiento() { return rutaAlmacenamiento; }
    public void setRutaAlmacenamiento(String rutaAlmacenamiento) { this.rutaAlmacenamiento = rutaAlmacenamiento; }
    public String getSha256() { return sha256; }
    public void setSha256(String sha256) { this.sha256 = sha256; }
    public String getTextoExtraido() { return textoExtraido; }
    public void setTextoExtraido(String textoExtraido) { this.textoExtraido = textoExtraido; }
    public Usuario getSubidoPor() { return subidoPor; }
    public void setSubidoPor(Usuario subidoPor) { this.subidoPor = subidoPor; }
    public LocalDateTime getSubidoEn() { return subidoEn; }
    public void setSubidoEn(LocalDateTime subidoEn) { this.subidoEn = subidoEn; }
}
