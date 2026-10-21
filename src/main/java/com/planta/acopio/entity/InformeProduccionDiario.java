package com.planta.acopio.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

// Raiz de agregado: virtualiza el cuadro en papel "INFORME DE PRODUCCION DE QUESOS DIARIO" -- un registro
// por dia calendario (UNIQUE(fecha)), agrupa N InformeProduccionInsumo (insumos usados ese dia). A
// diferencia de RegistroAcopio (inmutable), este SI se puede editar el mismo dia (decision explicita: el
// papel se corrige a mano, ej. "4 +2" en recorte en kilos) -- ver actualizadoPor/actualizadoEn.
@Entity
@Table(name = "informe_produccion_diario")
public class InformeProduccionDiario {

    @Id
    @GeneratedValue
    private UUID id;

    // Clave de idempotencia (mismo criterio que RegistroAcopio/Jornada/LoteProduccion): la web no tiene
    // cola offline, pero igual genera un uuid en el cliente para que un reintento de red tras un POST
    // incierto no duplique el informe.
    @Column(name = "uuid_cliente", nullable = false, unique = true, length = 36)
    private String uuidCliente;

    @Column(nullable = false, unique = true)
    private LocalDate fecha;

    @Column(name = "quesos_diarios", nullable = false)
    private Integer quesosDiarios;

    @Column(name = "quesos_reparados", nullable = false)
    private Integer quesosReparados = 0;

    @Column(name = "quesos_de_recorte", nullable = false)
    private Integer quesosDeRecorte = 0;

    @Column(name = "recorte_en_kilos", precision = 7, scale = 2)
    private BigDecimal recorteEnKilos;

    @ManyToOne(optional = false)
    @JoinColumn(name = "responsable_id")
    private Usuario responsable;

    @Column(name = "creado_en", nullable = false, updatable = false)
    private LocalDateTime creadoEn = LocalDateTime.now();

    @ManyToOne
    @JoinColumn(name = "actualizado_por_id")
    private Usuario actualizadoPor;

    @Column(name = "actualizado_en")
    private LocalDateTime actualizadoEn;

    // orphanRemoval=true: al editar, el service reemplaza el contenido completo de la coleccion
    // (clear + addAll) en vez de calcular un diff -- mas simple y suficiente para el tamano tipico
    // (unos pocos insumos por dia).
    @OneToMany(mappedBy = "informe", cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<InformeProduccionInsumo> insumos = new HashSet<>();

    public InformeProduccionDiario() {}

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getUuidCliente() { return uuidCliente; }
    public void setUuidCliente(String uuidCliente) { this.uuidCliente = uuidCliente; }
    public LocalDate getFecha() { return fecha; }
    public void setFecha(LocalDate fecha) { this.fecha = fecha; }
    public Integer getQuesosDiarios() { return quesosDiarios; }
    public void setQuesosDiarios(Integer quesosDiarios) { this.quesosDiarios = quesosDiarios; }
    public Integer getQuesosReparados() { return quesosReparados; }
    public void setQuesosReparados(Integer quesosReparados) { this.quesosReparados = quesosReparados; }
    public Integer getQuesosDeRecorte() { return quesosDeRecorte; }
    public void setQuesosDeRecorte(Integer quesosDeRecorte) { this.quesosDeRecorte = quesosDeRecorte; }
    public BigDecimal getRecorteEnKilos() { return recorteEnKilos; }
    public void setRecorteEnKilos(BigDecimal recorteEnKilos) { this.recorteEnKilos = recorteEnKilos; }
    public Usuario getResponsable() { return responsable; }
    public void setResponsable(Usuario responsable) { this.responsable = responsable; }
    public LocalDateTime getCreadoEn() { return creadoEn; }
    public void setCreadoEn(LocalDateTime creadoEn) { this.creadoEn = creadoEn; }
    public Usuario getActualizadoPor() { return actualizadoPor; }
    public void setActualizadoPor(Usuario actualizadoPor) { this.actualizadoPor = actualizadoPor; }
    public LocalDateTime getActualizadoEn() { return actualizadoEn; }
    public void setActualizadoEn(LocalDateTime actualizadoEn) { this.actualizadoEn = actualizadoEn; }
    public Set<InformeProduccionInsumo> getInsumos() { return insumos; }
    public void setInsumos(Set<InformeProduccionInsumo> insumos) { this.insumos = insumos; }
}
