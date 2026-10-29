package com.planta.acopio.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

// V43: lote de un insumo. Cada ingreso crea uno; el stock del insumo es la suma de sus lotes disponibles y cada movimiento
// del kardex referencia el lote que afecta. "Disponible" solo cambia junto con un movimiento (InventarioInsumoService), bajo el
// bloqueo de la fila del insumo. El estado (vigente / por vencer / vencido / agotado) se calcula al consultar: no se guarda.
@Entity
@Table(name = "lote_insumo")
public class LoteInsumo {

    public static final String CODIGO_INICIAL = "INICIAL";

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "insumo_id")
    private Insumo insumo;

    @Column(nullable = false, length = 40)
    private String codigo;

    @Column(name = "codigo_proveedor", length = 60)
    private String codigoProveedor;

    @Column(name = "fecha_ingreso", nullable = false)
    private LocalDate fechaIngreso;

    // Ultimo dia valido (inclusive), como ReglaVencimiento. Opcional.
    @Column(name = "fecha_vencimiento")
    private LocalDate fechaVencimiento;

    // V44: de que proveedor y documento de ingreso vino el lote (nulos en el INICIAL y en los ingresos anteriores a V44).
    @ManyToOne
    @JoinColumn(name = "proveedor_id")
    private ProveedorCompra proveedor;

    @ManyToOne
    @JoinColumn(name = "documento_ingreso_id")
    private DocumentoIngreso documentoIngreso;

    @Column(name = "cantidad_inicial", nullable = false, precision = 12, scale = 2)
    private BigDecimal cantidadInicial;

    @Column(name = "cantidad_disponible", nullable = false, precision = 12, scale = 2)
    private BigDecimal cantidadDisponible;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Origen origen = Origen.INGRESO;

    @Column(name = "creado_en", nullable = false, updatable = false)
    private LocalDateTime creadoEn = LocalDateTime.now();

    public enum Origen { INGRESO, INICIAL }

    public LoteInsumo() {}

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public Insumo getInsumo() { return insumo; }
    public void setInsumo(Insumo insumo) { this.insumo = insumo; }
    public String getCodigo() { return codigo; }
    public void setCodigo(String codigo) { this.codigo = codigo; }
    public String getCodigoProveedor() { return codigoProveedor; }
    public void setCodigoProveedor(String codigoProveedor) { this.codigoProveedor = codigoProveedor; }
    public LocalDate getFechaIngreso() { return fechaIngreso; }
    public void setFechaIngreso(LocalDate fechaIngreso) { this.fechaIngreso = fechaIngreso; }
    public LocalDate getFechaVencimiento() { return fechaVencimiento; }
    public void setFechaVencimiento(LocalDate fechaVencimiento) { this.fechaVencimiento = fechaVencimiento; }
    public ProveedorCompra getProveedor() { return proveedor; }
    public void setProveedor(ProveedorCompra proveedor) { this.proveedor = proveedor; }
    public DocumentoIngreso getDocumentoIngreso() { return documentoIngreso; }
    public void setDocumentoIngreso(DocumentoIngreso documentoIngreso) { this.documentoIngreso = documentoIngreso; }
    public BigDecimal getCantidadInicial() { return cantidadInicial; }
    public void setCantidadInicial(BigDecimal cantidadInicial) { this.cantidadInicial = cantidadInicial; }
    public BigDecimal getCantidadDisponible() { return cantidadDisponible; }
    public void setCantidadDisponible(BigDecimal cantidadDisponible) { this.cantidadDisponible = cantidadDisponible; }
    public Origen getOrigen() { return origen; }
    public void setOrigen(Origen origen) { this.origen = origen; }
    public LocalDateTime getCreadoEn() { return creadoEn; }
    public void setCreadoEn(LocalDateTime creadoEn) { this.creadoEn = creadoEn; }
}
