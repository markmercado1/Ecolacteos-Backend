package com.planta.acopio.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;

// Fase H (V33): evidencia append-only de una decision del titular (DS 016-2024-JUS art. 9: la prueba la tiene el responsable).
// El estado vigente de una finalidad es el de su ultima fila. REGISTRO_CLIENTE y COMUNICACIONES_COMERCIALES son independientes.
@Entity
@Table(name = "cliente_consentimiento")
public class ClienteConsentimiento {

    public static final String REGISTRO = "REGISTRO_CLIENTE";
    public static final String COMERCIAL = "COMUNICACIONES_COMERCIALES";
    public static final String OTORGADO = "OTORGADO";
    public static final String REVOCADO = "REVOCADO";

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "cliente_id", nullable = false)
    private UUID clienteId;

    @Column(nullable = false, length = 30)
    private String finalidad;

    @Column(nullable = false, length = 10)
    private String estado;

    @Column(length = 60)
    private String medios;

    @Column(nullable = false, length = 20)
    private String forma;

    @Column(name = "aviso_version", nullable = false, length = 20)
    private String avisoVersion;

    @Column(name = "aviso_completo", nullable = false)
    private boolean avisoCompleto;

    @Column(name = "registrado_por", nullable = false)
    private UUID registradoPor;

    @Column(name = "registrado_en", nullable = false, updatable = false)
    private LocalDateTime registradoEn = LocalDateTime.now();

    @Column(length = 300)
    private String nota;

    public ClienteConsentimiento() {}

    public UUID getId() { return id; }
    public UUID getClienteId() { return clienteId; }
    public void setClienteId(UUID clienteId) { this.clienteId = clienteId; }
    public String getFinalidad() { return finalidad; }
    public void setFinalidad(String finalidad) { this.finalidad = finalidad; }
    public String getEstado() { return estado; }
    public void setEstado(String estado) { this.estado = estado; }
    public String getMedios() { return medios; }
    public void setMedios(String medios) { this.medios = medios; }
    public String getForma() { return forma; }
    public void setForma(String forma) { this.forma = forma; }
    public String getAvisoVersion() { return avisoVersion; }
    public void setAvisoVersion(String avisoVersion) { this.avisoVersion = avisoVersion; }
    public boolean isAvisoCompleto() { return avisoCompleto; }
    public void setAvisoCompleto(boolean avisoCompleto) { this.avisoCompleto = avisoCompleto; }
    public UUID getRegistradoPor() { return registradoPor; }
    public void setRegistradoPor(UUID registradoPor) { this.registradoPor = registradoPor; }
    public LocalDateTime getRegistradoEn() { return registradoEn; }
    public String getNota() { return nota; }
    public void setNota(String nota) { this.nota = nota; }
}
