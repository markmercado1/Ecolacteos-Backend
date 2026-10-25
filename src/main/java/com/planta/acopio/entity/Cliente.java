package com.planta.acopio.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;

// Fase H (V33): ficha VOLUNTARIA de un comprador. DNI/RUC opcionales (si existen identifican con certeza y son unicos entre
// fichas vigentes). `mayorista` solo lo cambia ADMIN. Una ficha fusionada queda inactiva y apunta a su destino.
@Entity
@Table(name = "cliente")
public class Cliente {

    @Id
    @GeneratedValue
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "tipo_persona", nullable = false, length = 10)
    private TipoPersona tipoPersona = TipoPersona.NATURAL;

    @Column(nullable = false, length = 150)
    private String nombre;

    @Column(length = 8)
    private String dni;

    @Column(length = 11)
    private String ruc;

    @Column(name = "razon_social", length = 150)
    private String razonSocial;

    @Column(length = 120)
    private String correo;

    @Column(nullable = false)
    private boolean mayorista;

    @Column(name = "mayorista_cambiado_por")
    private UUID mayoristaCambiadoPor;

    @Column(name = "mayorista_cambiado_en")
    private LocalDateTime mayoristaCambiadoEn;

    @Column(name = "mayorista_motivo", length = 300)
    private String mayoristaMotivo;

    @Column(nullable = false)
    private boolean activo = true;

    @Column(name = "fusionado_en_id")
    private UUID fusionadoEnId;

    @Column(name = "creado_por")
    private UUID creadoPor;

    @Column(name = "creado_en", nullable = false, updatable = false)
    private LocalDateTime creadoEn = LocalDateTime.now();

    public Cliente() {}

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public TipoPersona getTipoPersona() { return tipoPersona; }
    public void setTipoPersona(TipoPersona tipoPersona) { this.tipoPersona = tipoPersona; }
    public String getNombre() { return nombre; }
    public void setNombre(String nombre) { this.nombre = nombre; }
    public String getDni() { return dni; }
    public void setDni(String dni) { this.dni = dni; }
    public String getRuc() { return ruc; }
    public void setRuc(String ruc) { this.ruc = ruc; }
    public String getRazonSocial() { return razonSocial; }
    public void setRazonSocial(String razonSocial) { this.razonSocial = razonSocial; }
    public String getCorreo() { return correo; }
    public void setCorreo(String correo) { this.correo = correo; }
    public boolean isMayorista() { return mayorista; }
    public void setMayorista(boolean mayorista) { this.mayorista = mayorista; }
    public UUID getMayoristaCambiadoPor() { return mayoristaCambiadoPor; }
    public void setMayoristaCambiadoPor(UUID mayoristaCambiadoPor) { this.mayoristaCambiadoPor = mayoristaCambiadoPor; }
    public LocalDateTime getMayoristaCambiadoEn() { return mayoristaCambiadoEn; }
    public void setMayoristaCambiadoEn(LocalDateTime mayoristaCambiadoEn) { this.mayoristaCambiadoEn = mayoristaCambiadoEn; }
    public String getMayoristaMotivo() { return mayoristaMotivo; }
    public void setMayoristaMotivo(String mayoristaMotivo) { this.mayoristaMotivo = mayoristaMotivo; }
    public boolean isActivo() { return activo; }
    public void setActivo(boolean activo) { this.activo = activo; }
    public UUID getFusionadoEnId() { return fusionadoEnId; }
    public void setFusionadoEnId(UUID fusionadoEnId) { this.fusionadoEnId = fusionadoEnId; }
    public UUID getCreadoPor() { return creadoPor; }
    public void setCreadoPor(UUID creadoPor) { this.creadoPor = creadoPor; }
    public LocalDateTime getCreadoEn() { return creadoEn; }
    public void setCreadoEn(LocalDateTime creadoEn) { this.creadoEn = creadoEn; }
}
