package com.planta.acopio.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

// Raiz de agregado (CORE_DESIGN.md §7). INMUTABLE una vez creado/sincronizado:
// no existe (ni debe existir) un metodo de actualizacion generico sobre esta
// entidad -- los unicos cambios posteriores son CorreccionRegistro (anexado,
// nunca reemplaza), y de forma derivada AnalisisCalidad y AlertaAnomalia.
@Entity
@Table(name = "registro_acopio")
public class RegistroAcopio {

    @Id
    @GeneratedValue
    private UUID id;

    // Clave de idempotencia offline-first: generado en el telefono.
    @Column(name = "uuid_cliente", nullable = false, unique = true, length = 36)
    private String uuidCliente;

    @ManyToOne(optional = false)
    @JoinColumn(name = "proveedor_id")
    private Proveedor proveedor;

    @ManyToOne(optional = false)
    @JoinColumn(name = "acopiador_id")
    private Usuario acopiador;

    @ManyToOne(optional = false)
    @JoinColumn(name = "unidad_id")
    private Unidad unidad;

    @Column(name = "fecha_hora", nullable = false)
    private LocalDateTime fechaHora;

    @Column(nullable = false, precision = 8, scale = 2)
    private BigDecimal litros;

    @Column(name = "gps_lat", precision = 9, scale = 6)
    private BigDecimal gpsLat;

    @Column(name = "gps_lng", precision = 9, scale = 6)
    private BigDecimal gpsLng;

    @ManyToOne
    @JoinColumn(name = "motivo_observacion_id")
    private MotivoObservacion motivoObservacion;

    // Fase 1 de jornada (docs/ESPEC_JORNADA_TRAZABILIDAD.md, Ecolacteos-Frontend): NULL para todo el
    // historico anterior a esta fase y para cualquier captura sin jornada activa -- asociacion opcional,
    // nunca obligatoria (ESPEC §23).
    @ManyToOne
    @JoinColumn(name = "jornada_id")
    private Jornada jornada;

    @Column(name = "litros_por_voz", nullable = false)
    private Boolean litrosPorVoz = false; // RF-INN-02

    @Column(name = "foto_url", length = 255)
    private String fotoUrl;

    @Column(name = "sincronizado_en", nullable = false)
    private LocalDateTime sincronizadoEn = LocalDateTime.now();

    @Column(name = "creado_en", nullable = false, updatable = false)
    private LocalDateTime creadoEn = LocalDateTime.now();

    public RegistroAcopio() {}

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getUuidCliente() { return uuidCliente; }
    public void setUuidCliente(String uuidCliente) { this.uuidCliente = uuidCliente; }
    public Proveedor getProveedor() { return proveedor; }
    public void setProveedor(Proveedor proveedor) { this.proveedor = proveedor; }
    public Usuario getAcopiador() { return acopiador; }
    public void setAcopiador(Usuario acopiador) { this.acopiador = acopiador; }
    public Unidad getUnidad() { return unidad; }
    public void setUnidad(Unidad unidad) { this.unidad = unidad; }
    public LocalDateTime getFechaHora() { return fechaHora; }
    public void setFechaHora(LocalDateTime fechaHora) { this.fechaHora = fechaHora; }
    public BigDecimal getLitros() { return litros; }
    public void setLitros(BigDecimal litros) { this.litros = litros; }
    public BigDecimal getGpsLat() { return gpsLat; }
    public void setGpsLat(BigDecimal gpsLat) { this.gpsLat = gpsLat; }
    public BigDecimal getGpsLng() { return gpsLng; }
    public void setGpsLng(BigDecimal gpsLng) { this.gpsLng = gpsLng; }
    public MotivoObservacion getMotivoObservacion() { return motivoObservacion; }
    public void setMotivoObservacion(MotivoObservacion motivoObservacion) { this.motivoObservacion = motivoObservacion; }
    public Jornada getJornada() { return jornada; }
    public void setJornada(Jornada jornada) { this.jornada = jornada; }
    public Boolean getLitrosPorVoz() { return litrosPorVoz; }
    public void setLitrosPorVoz(Boolean litrosPorVoz) { this.litrosPorVoz = litrosPorVoz; }
    public String getFotoUrl() { return fotoUrl; }
    public void setFotoUrl(String fotoUrl) { this.fotoUrl = fotoUrl; }
    public LocalDateTime getSincronizadoEn() { return sincronizadoEn; }
    public void setSincronizadoEn(LocalDateTime sincronizadoEn) { this.sincronizadoEn = sincronizadoEn; }
    public LocalDateTime getCreadoEn() { return creadoEn; }
    public void setCreadoEn(LocalDateTime creadoEn) { this.creadoEn = creadoEn; }
}
