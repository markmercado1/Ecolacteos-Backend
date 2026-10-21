package com.planta.acopio.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

// Raiz de agregado: agrupa N RegistroAcopio via lote_registro_acopio.
// Decision aprobada (v1): un RegistroAcopio pertenece a UN SOLO lote --
// el esquema N:M se mantiene, pero LoteProduccionService valida esa regla
// antes de vincular (ver service/LoteProduccionService.java).
@Entity
@Table(name = "lote_produccion")
public class LoteProduccion {

    @Id
    @GeneratedValue
    private UUID id;

    // mejora #1: capturable offline (Produccion en planta, decision aprobada).
    @Column(name = "uuid_cliente", nullable = false, unique = true, length = 36)
    private String uuidCliente;

    @Column(nullable = false)
    private LocalDate fecha;

    @ManyToOne(optional = false)
    @JoinColumn(name = "producto_id")
    private Producto producto;

    @Column(name = "litros_usados", nullable = false, precision = 9, scale = 2)
    private BigDecimal litrosUsados;

    // Fase 3 (V12__produccion_multi_presentacion.sql): reemplazado por LoteProduccionDetalle (una fila por
    // presentacion de salida, N por lote) -- la cantidad total de un lote historico de 1 sola salida sigue
    // siendo consultable como SUM(detalle.cantidadObtenida), nunca un campo aparte que pueda desincronizarse.
    // rendimientoPct/unidadesEsperadas/rendimientoEsperadoPctSnapshot de aqui abajo son valores HISTORICOS
    // preservados tal cual para lotes anteriores a esta migracion; para lotes nuevos solo se calculan cuando
    // el lote tiene exactamente una salida hacia una presentacion sin contenido declarado ("Pieza"/"Unidad",
    // el mismo shape que siempre tuvo el queso) Y el producto tiene rendimientoEsperadoPct configurado --
    // nunca solo por tener una unica salida (ver LoteProduccionService.crear()).
    @Column(name = "rendimiento_pct", precision = 5, scale = 2)
    private BigDecimal rendimientoPct;

    // Refactor fase 2 (V9, nuevo modelo funcional): snapshot al momento de producir, NULL para lotes
    // creados antes de esta migracion (ver LoteProduccionService.crear()). Un cambio futuro en
    // tipo_queso.rendimiento_esperado_pct nunca reescribe estos valores retroactivamente.
    @Column(name = "unidades_esperadas")
    private Integer unidadesEsperadas;

    @Column(name = "rendimiento_esperado_pct_snapshot", precision = 5, scale = 2)
    private BigDecimal rendimientoEsperadoPctSnapshot;

    @ManyToOne(optional = false)
    @JoinColumn(name = "responsable_id")
    private Usuario responsable;

    @Column(name = "creado_en", nullable = false, updatable = false)
    private LocalDateTime creadoEn = LocalDateTime.now();

    @ManyToMany
    @JoinTable(
        name = "lote_registro_acopio",
        joinColumns = @JoinColumn(name = "lote_id"),
        inverseJoinColumns = @JoinColumn(name = "registro_acopio_id")
    )
    private Set<RegistroAcopio> registrosVinculados = new HashSet<>();

    public LoteProduccion() {}

    @Column(name = "motivo_rendimiento", length = 500)
    private String motivoRendimiento;

    @Column(name = "revision_rendimiento", length = 500)
    private String revisionRendimiento;

    // V18 (recetas): ciclo de vida. Los lotes de un solo paso (movil, registro manual, historicos) son
    // FINALIZADA desde su creacion; solo un lote planificado desde receta pasa por PLANIFICADA/EN_PRODUCCION.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 15)
    private EstadoLote estado = EstadoLote.FINALIZADA;

    @ManyToOne
    @JoinColumn(name = "receta_id")
    private Receta receta;

    // Snapshot de la salida esperada por la receta al planificar (en unidades de la presentacion de la
    // receta). La salida REAL es SUM(lote_produccion_detalle), registrada solo al finalizar.
    @Column(name = "cantidad_esperada", precision = 12, scale = 3)
    private BigDecimal cantidadEsperada;

    @Column(name = "iniciado_en")
    private LocalDateTime iniciadoEn;

    @Column(name = "finalizado_en")
    private LocalDateTime finalizadoEn;

    @Column(name = "cancelado_en")
    private LocalDateTime canceladoEn;

    public EstadoLote getEstado() { return estado; }
    public void setEstado(EstadoLote estado) { this.estado = estado; }
    public Receta getReceta() { return receta; }
    public void setReceta(Receta receta) { this.receta = receta; }
    public BigDecimal getCantidadEsperada() { return cantidadEsperada; }
    public void setCantidadEsperada(BigDecimal cantidadEsperada) { this.cantidadEsperada = cantidadEsperada; }
    public LocalDateTime getIniciadoEn() { return iniciadoEn; }
    public void setIniciadoEn(LocalDateTime iniciadoEn) { this.iniciadoEn = iniciadoEn; }
    public LocalDateTime getFinalizadoEn() { return finalizadoEn; }
    public void setFinalizadoEn(LocalDateTime finalizadoEn) { this.finalizadoEn = finalizadoEn; }
    public LocalDateTime getCanceladoEn() { return canceladoEn; }
    public void setCanceladoEn(LocalDateTime canceladoEn) { this.canceladoEn = canceladoEn; }

    public String getMotivoRendimiento() { return motivoRendimiento; }
    public void setMotivoRendimiento(String motivo) { this.motivoRendimiento = motivo; }
    public String getRevisionRendimiento() { return revisionRendimiento; }
    public void setRevisionRendimiento(String revision) { this.revisionRendimiento = revision; }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getUuidCliente() { return uuidCliente; }
    public void setUuidCliente(String uuidCliente) { this.uuidCliente = uuidCliente; }
    public LocalDate getFecha() { return fecha; }
    public void setFecha(LocalDate fecha) { this.fecha = fecha; }
    public Producto getProducto() { return producto; }
    public void setProducto(Producto producto) { this.producto = producto; }
    public BigDecimal getLitrosUsados() { return litrosUsados; }
    public void setLitrosUsados(BigDecimal litrosUsados) { this.litrosUsados = litrosUsados; }
    public BigDecimal getRendimientoPct() { return rendimientoPct; }
    public void setRendimientoPct(BigDecimal rendimientoPct) { this.rendimientoPct = rendimientoPct; }
    public Integer getUnidadesEsperadas() { return unidadesEsperadas; }
    public void setUnidadesEsperadas(Integer unidadesEsperadas) { this.unidadesEsperadas = unidadesEsperadas; }
    public BigDecimal getRendimientoEsperadoPctSnapshot() { return rendimientoEsperadoPctSnapshot; }
    public void setRendimientoEsperadoPctSnapshot(BigDecimal rendimientoEsperadoPctSnapshot) { this.rendimientoEsperadoPctSnapshot = rendimientoEsperadoPctSnapshot; }
    public Usuario getResponsable() { return responsable; }
    public void setResponsable(Usuario responsable) { this.responsable = responsable; }
    public LocalDateTime getCreadoEn() { return creadoEn; }
    public void setCreadoEn(LocalDateTime creadoEn) { this.creadoEn = creadoEn; }
    public Set<RegistroAcopio> getRegistrosVinculados() { return registrosVinculados; }
    public void setRegistrosVinculados(Set<RegistroAcopio> registrosVinculados) { this.registrosVinculados = registrosVinculados; }
}
