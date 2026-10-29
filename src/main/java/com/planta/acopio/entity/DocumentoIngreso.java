package com.planta.acopio.entity;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

// V44: cabecera de un ingreso de insumos. Sus lineas son los lotes (lote_insumo.documento_ingreso_id) que creo cada insumo recibido.
@Entity
@Table(name = "documento_ingreso")
public class DocumentoIngreso {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne
    @JoinColumn(name = "proveedor_id")
    private ProveedorCompra proveedor;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 15)
    private TipoDocumentoIngreso tipo = TipoDocumentoIngreso.SIN_DOCUMENTO;

    @Column(length = 40)
    private String numero;

    @Column(name = "fecha_documento")
    private LocalDate fechaDocumento;

    @Column(length = 500)
    private String observacion;

    @ManyToOne(optional = false)
    @JoinColumn(name = "usuario_id")
    private Usuario usuario;

    @Column(name = "uuid_cliente", unique = true)
    private UUID uuidCliente;

    @Column(name = "creado_en", nullable = false, updatable = false)
    private LocalDateTime creadoEn = LocalDateTime.now();

    public DocumentoIngreso() {}

    public UUID getId() { return id; }
    public ProveedorCompra getProveedor() { return proveedor; }
    public void setProveedor(ProveedorCompra proveedor) { this.proveedor = proveedor; }
    public TipoDocumentoIngreso getTipo() { return tipo; }
    public void setTipo(TipoDocumentoIngreso tipo) { this.tipo = tipo; }
    public String getNumero() { return numero; }
    public void setNumero(String numero) { this.numero = numero; }
    public LocalDate getFechaDocumento() { return fechaDocumento; }
    public void setFechaDocumento(LocalDate fechaDocumento) { this.fechaDocumento = fechaDocumento; }
    public String getObservacion() { return observacion; }
    public void setObservacion(String observacion) { this.observacion = observacion; }
    public Usuario getUsuario() { return usuario; }
    public void setUsuario(Usuario usuario) { this.usuario = usuario; }
    public UUID getUuidCliente() { return uuidCliente; }
    public void setUuidCliente(UUID uuidCliente) { this.uuidCliente = uuidCliente; }
    public LocalDateTime getCreadoEn() { return creadoEn; }
    public void setCreadoEn(LocalDateTime creadoEn) { this.creadoEn = creadoEn; }
}
