package com.planta.acopio.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

// Raiz de agregado (docs/ESPEC_JORNADA_TRAZABILIDAD.md, Ecolacteos-Frontend): un viaje concreto de
// recoleccion de leche de un acopiador con un vehiculo. Agrupa N RegistroAcopio (ver
// RegistroAcopio.jornada, FK simple -- no hace falta una tabla puente como lote_registro_acopio porque un
// RegistroAcopio pertenece a lo sumo a UNA jornada, nunca a varias).
@Entity
@Table(name = "jornada")
public class Jornada {

    @Id
    @GeneratedValue
    private UUID id;

    // Clave de idempotencia offline-first, igual que RegistroAcopio.uuidCliente.
    @Column(name = "uuid_cliente", nullable = false, unique = true, length = 36)
    private String uuidCliente;

    @ManyToOne(optional = false)
    @JoinColumn(name = "acopiador_id")
    private Usuario acopiador;

    @ManyToOne(optional = false)
    @JoinColumn(name = "unidad_id")
    private Unidad unidad;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EstadoJornada estado = EstadoJornada.EN_RECOLECCION;

    @Column(name = "hora_inicio", nullable = false)
    private LocalDateTime horaInicio;

    @Column(name = "gps_lat_inicio", precision = 9, scale = 6)
    private BigDecimal gpsLatInicio;

    @Column(name = "gps_lng_inicio", precision = 9, scale = 6)
    private BigDecimal gpsLngInicio;

    @Column(name = "hora_fin")
    private LocalDateTime horaFin;

    @Column(name = "gps_lat_fin", precision = 9, scale = 6)
    private BigDecimal gpsLatFin;

    @Column(name = "gps_lng_fin", precision = 9, scale = 6)
    private BigDecimal gpsLngFin;

    @Column(name = "motivo_cancelacion", columnDefinition = "TEXT")
    private String motivoCancelacion;

    @Column(name = "creado_en", nullable = false, updatable = false)
    private LocalDateTime creadoEn = LocalDateTime.now();

    public Jornada() {}

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getUuidCliente() { return uuidCliente; }
    public void setUuidCliente(String uuidCliente) { this.uuidCliente = uuidCliente; }
    public Usuario getAcopiador() { return acopiador; }
    public void setAcopiador(Usuario acopiador) { this.acopiador = acopiador; }
    public Unidad getUnidad() { return unidad; }
    public void setUnidad(Unidad unidad) { this.unidad = unidad; }
    public EstadoJornada getEstado() { return estado; }
    public void setEstado(EstadoJornada estado) { this.estado = estado; }
    public LocalDateTime getHoraInicio() { return horaInicio; }
    public void setHoraInicio(LocalDateTime horaInicio) { this.horaInicio = horaInicio; }
    public BigDecimal getGpsLatInicio() { return gpsLatInicio; }
    public void setGpsLatInicio(BigDecimal gpsLatInicio) { this.gpsLatInicio = gpsLatInicio; }
    public BigDecimal getGpsLngInicio() { return gpsLngInicio; }
    public void setGpsLngInicio(BigDecimal gpsLngInicio) { this.gpsLngInicio = gpsLngInicio; }
    public LocalDateTime getHoraFin() { return horaFin; }
    public void setHoraFin(LocalDateTime horaFin) { this.horaFin = horaFin; }
    public BigDecimal getGpsLatFin() { return gpsLatFin; }
    public void setGpsLatFin(BigDecimal gpsLatFin) { this.gpsLatFin = gpsLatFin; }
    public BigDecimal getGpsLngFin() { return gpsLngFin; }
    public void setGpsLngFin(BigDecimal gpsLngFin) { this.gpsLngFin = gpsLngFin; }
    public String getMotivoCancelacion() { return motivoCancelacion; }
    public void setMotivoCancelacion(String motivoCancelacion) { this.motivoCancelacion = motivoCancelacion; }
    public LocalDateTime getCreadoEn() { return creadoEn; }
    public void setCreadoEn(LocalDateTime creadoEn) { this.creadoEn = creadoEn; }
}
