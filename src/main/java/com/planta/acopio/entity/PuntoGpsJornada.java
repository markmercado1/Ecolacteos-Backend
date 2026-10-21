package com.planta.acopio.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

// Fase 3 (docs/ESPEC_JORNADA_TRAZABILIDAD.md, Ecolacteos-Frontend): un punto del recorrido GPS capturado
// periodicamente durante una Jornada EN_RECOLECCION. El acopiador/vehiculo del punto se infieren de
// `jornada` (Jornada.acopiador/Jornada.unidad) -- no se duplican acá, igual criterio documentado en
// V5__punto_gps_jornada.sql.
@Entity
@Table(name = "punto_gps_jornada")
public class PuntoGpsJornada {

    @Id
    @GeneratedValue
    private UUID id;

    // Clave de idempotencia offline-first, igual que Jornada.uuidCliente/RegistroAcopio.uuidCliente.
    @Column(name = "uuid_cliente", nullable = false, unique = true, length = 36)
    private String uuidCliente;

    @ManyToOne(optional = false)
    @JoinColumn(name = "jornada_id")
    private Jornada jornada;

    @Column(nullable = false, precision = 9, scale = 6)
    private BigDecimal lat;

    @Column(nullable = false, precision = 9, scale = 6)
    private BigDecimal lng;

    @Column(name = "precision_m", precision = 7, scale = 2)
    private BigDecimal precisionM;

    @Column(name = "capturado_en", nullable = false)
    private LocalDateTime capturadoEn;

    @Column(name = "creado_en", nullable = false, updatable = false)
    private LocalDateTime creadoEn = LocalDateTime.now();

    public PuntoGpsJornada() {}

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getUuidCliente() { return uuidCliente; }
    public void setUuidCliente(String uuidCliente) { this.uuidCliente = uuidCliente; }
    public Jornada getJornada() { return jornada; }
    public void setJornada(Jornada jornada) { this.jornada = jornada; }
    public BigDecimal getLat() { return lat; }
    public void setLat(BigDecimal lat) { this.lat = lat; }
    public BigDecimal getLng() { return lng; }
    public void setLng(BigDecimal lng) { this.lng = lng; }
    public BigDecimal getPrecisionM() { return precisionM; }
    public void setPrecisionM(BigDecimal precisionM) { this.precisionM = precisionM; }
    public LocalDateTime getCapturadoEn() { return capturadoEn; }
    public void setCapturadoEn(LocalDateTime capturadoEn) { this.capturadoEn = capturadoEn; }
    public LocalDateTime getCreadoEn() { return creadoEn; }
    public void setCreadoEn(LocalDateTime creadoEn) { this.creadoEn = creadoEn; }
}
