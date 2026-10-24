package com.planta.acopio.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

// Pagos efectivos: dinero realmente entregado a un proveedor (V30). Los importes no se editan: anular cambia el estado y deja motivo,
// usuario y fecha, sin borrar la fila. No guarda cuentas completas: solo banco y numero de operacion.
@Entity
@Table(name = "pago_proveedor")
public class PagoProveedor {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "uuid_cliente", nullable = false, unique = true, updatable = false, length = 36)
    private String uuidCliente;

    @ManyToOne(optional = false)
    @JoinColumn(name = "proveedor_id", updatable = false)
    private Proveedor proveedor;

    @Column(name = "fecha_hora", nullable = false, updatable = false)
    private LocalDateTime fechaHora = LocalDateTime.now();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 20)
    private ModalidadPagoProveedor modalidad;

    @Column(nullable = false, updatable = false, precision = 12, scale = 2)
    private BigDecimal monto;

    @Column(updatable = false, length = 200)
    private String concepto;

    @Column(updatable = false, length = 80)
    private String banco;

    @Column(name = "nro_operacion", updatable = false, length = 80)
    private String nroOperacion;

    @Column(name = "nro_recibo", updatable = false, length = 80)
    private String nroRecibo;

    @Column(updatable = false, length = 500)
    private String observacion;

    @ManyToOne(optional = false)
    @JoinColumn(name = "usuario_id", updatable = false)
    private Usuario usuario;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private EstadoPagoProveedor estado = EstadoPagoProveedor.REGISTRADO;

    @Column(name = "anulado_motivo", columnDefinition = "TEXT")
    private String anuladoMotivo;

    @ManyToOne
    @JoinColumn(name = "anulado_por")
    private Usuario anuladoPor;

    @Column(name = "anulado_en")
    private LocalDateTime anuladoEn;

    @Column(name = "creado_en", nullable = false, updatable = false)
    private LocalDateTime creadoEn = LocalDateTime.now();

    public PagoProveedor() {}

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getUuidCliente() { return uuidCliente; }
    public void setUuidCliente(String uuidCliente) { this.uuidCliente = uuidCliente; }
    public Proveedor getProveedor() { return proveedor; }
    public void setProveedor(Proveedor proveedor) { this.proveedor = proveedor; }
    public LocalDateTime getFechaHora() { return fechaHora; }
    public void setFechaHora(LocalDateTime fechaHora) { this.fechaHora = fechaHora; }
    public ModalidadPagoProveedor getModalidad() { return modalidad; }
    public void setModalidad(ModalidadPagoProveedor modalidad) { this.modalidad = modalidad; }
    public BigDecimal getMonto() { return monto; }
    public void setMonto(BigDecimal monto) { this.monto = monto; }
    public String getConcepto() { return concepto; }
    public void setConcepto(String concepto) { this.concepto = concepto; }
    public String getBanco() { return banco; }
    public void setBanco(String banco) { this.banco = banco; }
    public String getNroOperacion() { return nroOperacion; }
    public void setNroOperacion(String nroOperacion) { this.nroOperacion = nroOperacion; }
    public String getNroRecibo() { return nroRecibo; }
    public void setNroRecibo(String nroRecibo) { this.nroRecibo = nroRecibo; }
    public String getObservacion() { return observacion; }
    public void setObservacion(String observacion) { this.observacion = observacion; }
    public Usuario getUsuario() { return usuario; }
    public void setUsuario(Usuario usuario) { this.usuario = usuario; }
    public EstadoPagoProveedor getEstado() { return estado; }
    public void setEstado(EstadoPagoProveedor estado) { this.estado = estado; }
    public String getAnuladoMotivo() { return anuladoMotivo; }
    public void setAnuladoMotivo(String anuladoMotivo) { this.anuladoMotivo = anuladoMotivo; }
    public Usuario getAnuladoPor() { return anuladoPor; }
    public void setAnuladoPor(Usuario anuladoPor) { this.anuladoPor = anuladoPor; }
    public LocalDateTime getAnuladoEn() { return anuladoEn; }
    public void setAnuladoEn(LocalDateTime anuladoEn) { this.anuladoEn = anuladoEn; }
    public LocalDateTime getCreadoEn() { return creadoEn; }
    public void setCreadoEn(LocalDateTime creadoEn) { this.creadoEn = creadoEn; }
}
