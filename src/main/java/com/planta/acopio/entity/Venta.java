package com.planta.acopio.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.DynamicInsert;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

// Fase 1 (Producto + Presentacion): tipoQueso -> producto (rename, V10). Fase 3 (Produccion con varias
// presentaciones de salida, V12__produccion_multi_presentacion.sql): producto -> presentacion -- ahora que
// Inventario controla stock por presentacion (no por producto), la venta debe descontar y validar la MISMA
// presentacion que el usuario selecciono, nunca "una" presentacion generica del producto.
//
// Fase 4 (Venta multi-linea / carrito, V15__venta_multi_linea.sql): una venta corresponde a UNA operacion de
// venta pero puede tener VARIAS lineas (ver VentaDetalle) -- presentacion/cantidad/precioUnitario se
// retiran de aqui, una fila de VentaDetalle por presentacion vendida. `total` deja de ser una columna
// GENERADA por Postgres (no puede expresar una suma de filas de OTRA tabla) y pasa a fijarlo VentaService,
// como suma de los subtotales de cada linea. El contrato movil (POST /api/ventas, una sola linea) no cambia
// -- internamente ahora crea una venta con exactamente una fila de VentaDetalle (ver VentaService).
//
// V48: comprobante (nota de venta o boleta) con serie y numero correlativo. @DynamicInsert: un INSERT que no los mencione (la app
// movil, las pruebas) recibe los valores por defecto de la BD (NOTA_VENTA / NV01 / siguiente numero); VentaService siempre los fija.
// Las columnas no llevan nullable=false aqui por el mismo motivo: Hibernate no debe exigirlas antes de que la BD las rellene.
@Entity
@DynamicInsert
@Table(name = "venta")
public class Venta {

    @Id
    @GeneratedValue
    private UUID id;

    // mejora #1: capturable offline (Ventas en punto de venta, decision aprobada).
    @Column(name = "uuid_cliente", nullable = false, unique = true, length = 36)
    private String uuidCliente;

    @Column(nullable = false)
    private LocalDate fecha;

    @Column(name = "tipo_cliente", nullable = false, length = 15)
    private String tipoCliente;

    // Suma de los subtotales de cada VentaDetalle, fijada por VentaService al confirmar la venta (ver
    // comentario de clase). Nunca se recalcula despues (append-only, igual que el resto del libro mayor).
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal total;

    @ManyToOne(optional = false)
    @JoinColumn(name = "vendedor_id")
    private Usuario vendedor;

    // Fase H (V33): comprador (null = cliente varios), reserva de origen y copia historica del nombre/RUC usados en ESTA venta.
    @Enumerated(EnumType.STRING)
    @Column(name = "comprador_tipo", length = 10)
    private TipoComprador compradorTipo;

    @Column(name = "cliente_id")
    private UUID clienteId;

    @Column(name = "proveedor_id")
    private UUID proveedorId;

    @Column(name = "reserva_id")
    private UUID reservaId;

    @Column(name = "comprador_nombre", length = 150)
    private String compradorNombre;

    @Column(name = "comprador_ruc", length = 11)
    private String compradorRuc;

    @Enumerated(EnumType.STRING)
    @Column(name = "tipo_comprobante", length = 15)
    private TipoComprobante tipoComprobante;

    @Column(length = 4)
    private String serie;

    private Long numero;

    // Solo lectura: la BD la rellena al insertar (V25, clock_timestamp()); es la hora real del registro.
    @Column(name = "creado_en", insertable = false, updatable = false)
    private LocalDateTime creadoEn;

    public Venta() {}

    public TipoComprobante getTipoComprobante() { return tipoComprobante; }
    public void setTipoComprobante(TipoComprobante tipoComprobante) { this.tipoComprobante = tipoComprobante; }
    public String getSerie() { return serie; }
    public void setSerie(String serie) { this.serie = serie; }
    public Long getNumero() { return numero; }
    public void setNumero(Long numero) { this.numero = numero; }
    public LocalDateTime getCreadoEn() { return creadoEn; }
    /** "NV01-00000041"; null si la venta aun no tiene numero (solo antes de insertarse). */
    public String comprobante() { return TipoComprobante.formatear(serie, numero); }

    public TipoComprador getCompradorTipo() { return compradorTipo; }
    public void setCompradorTipo(TipoComprador compradorTipo) { this.compradorTipo = compradorTipo; }
    public UUID getClienteId() { return clienteId; }
    public void setClienteId(UUID clienteId) { this.clienteId = clienteId; }
    public UUID getProveedorId() { return proveedorId; }
    public void setProveedorId(UUID proveedorId) { this.proveedorId = proveedorId; }
    public UUID getReservaId() { return reservaId; }
    public void setReservaId(UUID reservaId) { this.reservaId = reservaId; }
    public String getCompradorNombre() { return compradorNombre; }
    public void setCompradorNombre(String compradorNombre) { this.compradorNombre = compradorNombre; }
    public String getCompradorRuc() { return compradorRuc; }
    public void setCompradorRuc(String compradorRuc) { this.compradorRuc = compradorRuc; }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getUuidCliente() { return uuidCliente; }
    public void setUuidCliente(String uuidCliente) { this.uuidCliente = uuidCliente; }
    public LocalDate getFecha() { return fecha; }
    public void setFecha(LocalDate fecha) { this.fecha = fecha; }
    public String getTipoCliente() { return tipoCliente; }
    public void setTipoCliente(String tipoCliente) { this.tipoCliente = tipoCliente; }
    public BigDecimal getTotal() { return total; }
    public void setTotal(BigDecimal total) { this.total = total; }
    public Usuario getVendedor() { return vendedor; }
    public void setVendedor(Usuario vendedor) { this.vendedor = vendedor; }
}
