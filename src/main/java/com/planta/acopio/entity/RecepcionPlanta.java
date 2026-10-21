package com.planta.acopio.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "recepcion_planta", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"fecha", "unidad_id", "turno"})
})
public class RecepcionPlanta {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false)
    private LocalDate fecha;

    @Column(nullable = false, length = 10)
    private String turno = "UNICO";

    @ManyToOne(optional = false)
    @JoinColumn(name = "unidad_id")
    private Unidad unidad;

    @Column(name = "litros_campo", nullable = false, precision = 9, scale = 2)
    private BigDecimal litrosCampo;

    @Column(name = "litros_planta", nullable = false, precision = 9, scale = 2)
    private BigDecimal litrosPlanta;

    // Columna generada por PostgreSQL (STORED) -- solo lectura desde JPA.
    // Ver CORE_DESIGN.md §5/§6 (hallazgo 3.6): litros_campo = 0 puede reportar
    // 0% aun con litros_planta > 0; ConciliacionService corrige ese caso borde
    // forzando ALERTA independientemente de este valor.
    @Column(name = "diferencia_pct", insertable = false, updatable = false, precision = 5, scale = 2)
    private BigDecimal diferenciaPct;

    @ManyToOne(optional = false)
    @JoinColumn(name = "responsable_id")
    private Usuario responsable;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private EstadoConciliacion estado = EstadoConciliacion.OK;

    @Column(name = "creado_en", nullable = false, updatable = false)
    private LocalDateTime creadoEn = LocalDateTime.now();

    // Fase 3, decision de negocio 3 (opcion B, confirmada): trazabilidad
    // exacta -- que RegistroAcopio individuales componen esta recepcion.
    // Seleccion explicita (RECEPCION los indica al conciliar), mismo patron
    // que Pago.registrosLiquidados / LoteProduccion.registrosVinculados.
    @ManyToMany
    @JoinTable(
        name = "recepcion_planta_registro_acopio",
        joinColumns = @JoinColumn(name = "recepcion_planta_id"),
        inverseJoinColumns = @JoinColumn(name = "registro_acopio_id")
    )
    private Set<RegistroAcopio> registrosVinculados = new HashSet<>();

    // Fase 2 (docs/ESPEC_JORNADA_TRAZABILIDAD.md, Ecolacteos-Frontend): vinculo unico y opcional con la
    // Jornada que esta recepcion cierra. NULL para todo el historico anterior a esta fase (conciliaciones
    // sin jornada, ESPEC §23) -- el cerrojo real de unicidad es el indice parcial unico de
    // V4__recepcion_jornada.sql, esta anotacion no lo repite para no chocar con Flyway como autoridad de
    // esquema. Cuando esta vinculada, litrosCampo deja de ser un valor digitado a mano: es la suma
    // autoritativa de RegistroAcopio.litros de esta jornada (ver ConciliacionService.registrarRecepcionDeJornada).
    @ManyToOne
    @JoinColumn(name = "jornada_id")
    private Jornada jornada;

    // Clave de idempotencia offline-first del flujo de jornada (unico consumidor -- el flujo historico de
    // conciliacion manual nunca la tuvo ni la necesita, sigue siendo online-only puro).
    @Column(name = "uuid_cliente", length = 36)
    private String uuidCliente;

    // Marca temporal real de llegada a planta (ESPEC §15): distinta de Jornada.horaFin (fin de recoleccion).
    // NULL para el historico sin jornada, que nunca distinguio ambos eventos.
    @Column(name = "hora_llegada")
    private LocalDateTime horaLlegada;

    // Opcional: solo tiene sentido explicar una diferencia real entre litrosCampo (esperado) y litrosPlanta
    // (real) cuando la hay -- nunca obligatorio.
    @Column(columnDefinition = "TEXT")
    private String observacion;

    public RecepcionPlanta() {}

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public LocalDate getFecha() { return fecha; }
    public void setFecha(LocalDate fecha) { this.fecha = fecha; }
    public String getTurno() { return turno; }
    public void setTurno(String turno) { this.turno = turno; }
    public Unidad getUnidad() { return unidad; }
    public void setUnidad(Unidad unidad) { this.unidad = unidad; }
    public BigDecimal getLitrosCampo() { return litrosCampo; }
    public void setLitrosCampo(BigDecimal litrosCampo) { this.litrosCampo = litrosCampo; }
    public BigDecimal getLitrosPlanta() { return litrosPlanta; }
    public void setLitrosPlanta(BigDecimal litrosPlanta) { this.litrosPlanta = litrosPlanta; }
    public BigDecimal getDiferenciaPct() { return diferenciaPct; }
    public Usuario getResponsable() { return responsable; }
    public void setResponsable(Usuario responsable) { this.responsable = responsable; }
    public EstadoConciliacion getEstado() { return estado; }
    public void setEstado(EstadoConciliacion estado) { this.estado = estado; }
    public LocalDateTime getCreadoEn() { return creadoEn; }
    public void setCreadoEn(LocalDateTime creadoEn) { this.creadoEn = creadoEn; }
    public Set<RegistroAcopio> getRegistrosVinculados() { return registrosVinculados; }
    public void setRegistrosVinculados(Set<RegistroAcopio> registrosVinculados) { this.registrosVinculados = registrosVinculados; }
    public Jornada getJornada() { return jornada; }
    public void setJornada(Jornada jornada) { this.jornada = jornada; }
    public String getUuidCliente() { return uuidCliente; }
    public void setUuidCliente(String uuidCliente) { this.uuidCliente = uuidCliente; }
    public LocalDateTime getHoraLlegada() { return horaLlegada; }
    public void setHoraLlegada(LocalDateTime horaLlegada) { this.horaLlegada = horaLlegada; }
    public String getObservacion() { return observacion; }
    public void setObservacion(String observacion) { this.observacion = observacion; }
}
