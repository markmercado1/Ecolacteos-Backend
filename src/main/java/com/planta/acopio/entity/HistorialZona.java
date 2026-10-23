package com.planta.acopio.entity;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

// Sin endpoint REST propio de escritura (CORE_DESIGN.md §7): se crea/cierra desde
// ProveedorService al reasignar la zona de un proveedor.
//
// Un periodo es el intervalo de INSTANTES [inicioEn, finEn) (finEn nulo = periodo abierto). desde/hasta son los dias
// equivalentes que ya mostraba la API (hasta = ultimo dia completo, nunca anterior a desde). Un periodo con
// requiereRevision viene del codigo anterior a V27 y no se puede ordenar: se conserva pero nunca se usa para decidir
// la zona de una entrega.
@Entity
@Table(name = "historial_zona")
public class HistorialZona {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "proveedor_id")
    private Proveedor proveedor;

    @ManyToOne(optional = false)
    @JoinColumn(name = "zona_id")
    private Zona zona;

    @Column(nullable = false)
    private LocalDate desde;

    private LocalDate hasta;

    @Column(name = "inicio_en", nullable = false)
    private LocalDateTime inicioEn;

    @Column(name = "fin_en")
    private LocalDateTime finEn;

    @ManyToOne
    @JoinColumn(name = "zona_origen_id")
    private Zona zonaOrigen;

    @Column(length = 200)
    private String motivo;

    @ManyToOne
    @JoinColumn(name = "usuario_id")
    private Usuario usuario;

    @Column(name = "requiere_revision", nullable = false)
    private Boolean requiereRevision = false;

    @Column(name = "nota_revision", length = 300)
    private String notaRevision;

    public HistorialZona() {}

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public Proveedor getProveedor() { return proveedor; }
    public void setProveedor(Proveedor proveedor) { this.proveedor = proveedor; }
    public Zona getZona() { return zona; }
    public void setZona(Zona zona) { this.zona = zona; }
    public LocalDate getDesde() { return desde; }
    public void setDesde(LocalDate desde) { this.desde = desde; }
    public LocalDate getHasta() { return hasta; }
    public void setHasta(LocalDate hasta) { this.hasta = hasta; }
    public LocalDateTime getInicioEn() { return inicioEn; }
    public void setInicioEn(LocalDateTime inicioEn) { this.inicioEn = inicioEn; }
    public LocalDateTime getFinEn() { return finEn; }
    public void setFinEn(LocalDateTime finEn) { this.finEn = finEn; }
    public Zona getZonaOrigen() { return zonaOrigen; }
    public void setZonaOrigen(Zona zonaOrigen) { this.zonaOrigen = zonaOrigen; }
    public String getMotivo() { return motivo; }
    public void setMotivo(String motivo) { this.motivo = motivo; }
    public Usuario getUsuario() { return usuario; }
    public void setUsuario(Usuario usuario) { this.usuario = usuario; }
    public Boolean getRequiereRevision() { return requiereRevision; }
    public void setRequiereRevision(Boolean requiereRevision) { this.requiereRevision = requiereRevision; }
    public String getNotaRevision() { return notaRevision; }
    public void setNotaRevision(String notaRevision) { this.notaRevision = notaRevision; }
}
