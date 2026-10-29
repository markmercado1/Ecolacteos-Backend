package com.planta.acopio.service;

import com.planta.acopio.dto.AdjuntoResponse;
import com.planta.acopio.entity.Adjunto;
import com.planta.acopio.entity.Usuario;
import com.planta.acopio.exception.ConflictoNegocioException;
import com.planta.acopio.exception.RecursoNoEncontradoException;
import com.planta.acopio.exception.ValidacionNegocioException;
import com.planta.acopio.repository.AdjuntoRepository;
import com.planta.acopio.repository.DocumentoIngresoRepository;
import com.planta.acopio.repository.UsuarioRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Adjuntos (V46): foto o PDF del recibo, factura o guia de un documento de ingreso. El archivo se guarda en una carpeta local FUERA
 * del codigo y de la base de datos (app.adjuntos.directorio, o la variable de entorno APP_ADJUNTOS_DIRECTORIO); la ubicacion
 * definitiva y su respaldo se definen al desplegar a produccion. Imagenes JPEG/PNG/WEBP o PDF, maximo 10 MB; el tipo se valida por
 * el CONTENIDO (firma del archivo), no por la extension ni por lo que declare el cliente. El nombre en disco lo genera el servidor.
 * El texto extraido (OCR) queda nulo: reservado para el futuro.
 */
@Service
public class AdjuntoService {

    public static final long MAX_BYTES = 10L * 1024 * 1024;
    static final int MAX_POR_DOCUMENTO = 10;
    private static final Map<String, String> EXTENSIONES = Map.of(
            "image/jpeg", ".jpg", "image/png", ".png", "image/webp", ".webp", "application/pdf", ".pdf");

    private final AdjuntoRepository repository;
    private final DocumentoIngresoRepository documentoRepository;
    private final UsuarioRepository usuarioRepository;
    private final Path directorio;

    public AdjuntoService(AdjuntoRepository repository, DocumentoIngresoRepository documentoRepository, UsuarioRepository usuarioRepository,
                          @Value("${app.adjuntos.directorio:uploads/adjuntos}") String directorio) {
        this.repository = repository;
        this.documentoRepository = documentoRepository;
        this.usuarioRepository = usuarioRepository;
        this.directorio = Path.of(directorio).toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.directorio);
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo crear la carpeta de adjuntos: " + this.directorio, e);
        }
    }

    @Transactional
    public AdjuntoResponse adjuntarADocumentoIngreso(UUID documentoId, MultipartFile archivo) {
        if (!documentoRepository.existsById(documentoId)) {
            throw new RecursoNoEncontradoException("Documento de ingreso no encontrado: " + documentoId);
        }
        if (archivo == null || archivo.isEmpty()) {
            throw new ValidacionNegocioException("El archivo está vacío.");
        }
        if (archivo.getSize() > MAX_BYTES) {
            throw new ValidacionNegocioException("El archivo supera el máximo de 10 MB.");
        }
        byte[] bytes;
        try {
            bytes = archivo.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo leer el archivo", e);
        }
        String tipo = detectarTipo(bytes);
        if (tipo == null) {
            throw new ValidacionNegocioException("Solo se aceptan imágenes JPG, PNG o WEBP y archivos PDF.");
        }
        if (repository.countByEntidadTipoAndEntidadId(Adjunto.DOCUMENTO_INGRESO, documentoId) >= MAX_POR_DOCUMENTO) {
            throw new ValidacionNegocioException("Un documento admite hasta " + MAX_POR_DOCUMENTO + " adjuntos.");
        }
        String sha = sha256(bytes);
        if (repository.existsByEntidadTipoAndEntidadIdAndSha256(Adjunto.DOCUMENTO_INGRESO, documentoId, sha)) {
            throw new ConflictoNegocioException("Ese archivo ya está adjunto a este documento.");
        }

        // Nombre generado por el servidor (nunca el del cliente): sin path traversal. Subcarpeta por año y mes.
        LocalDate hoy = LocalDate.now();
        String relativa = String.format("%d/%02d/%s%s", hoy.getYear(), hoy.getMonthValue(), UUID.randomUUID(), EXTENSIONES.get(tipo));
        Path destino = directorio.resolve(relativa).normalize();
        if (!destino.startsWith(directorio)) {
            throw new ValidacionNegocioException("Ruta de adjunto inválida.");
        }
        try {
            Files.createDirectories(destino.getParent());
            Files.write(destino, bytes);
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo guardar el adjunto", e);
        }

        Adjunto a = new Adjunto();
        a.setEntidadTipo(Adjunto.DOCUMENTO_INGRESO);
        a.setEntidadId(documentoId);
        a.setNombreOriginal(nombreSeguro(archivo.getOriginalFilename()));
        a.setContentType(tipo);
        a.setTamanoBytes((long) bytes.length);
        a.setRutaAlmacenamiento(relativa);
        a.setSha256(sha);
        a.setSubidoPor(usuarioAutenticado());
        return aRespuesta(repository.save(a));
    }

    @Transactional(readOnly = true)
    public List<AdjuntoResponse> listarDeDocumentoIngreso(UUID documentoId) {
        return repository.findByEntidadTipoAndEntidadIdOrderBySubidoEnAscIdAsc(Adjunto.DOCUMENTO_INGRESO, documentoId).stream()
                .map(AdjuntoService::aRespuesta).toList();
    }

    /** Contenido de un adjunto: ruta en disco, tipo y nombre para la descarga. */
    @Transactional(readOnly = true)
    public Contenido contenido(UUID id) {
        Adjunto a = repository.findById(id).orElseThrow(() -> new RecursoNoEncontradoException("Adjunto no encontrado: " + id));
        Path archivo = directorio.resolve(a.getRutaAlmacenamiento()).normalize();
        if (!archivo.startsWith(directorio) || !Files.isRegularFile(archivo)) {
            throw new RecursoNoEncontradoException("El archivo del adjunto no está disponible en el servidor.");
        }
        return new Contenido(archivo, a.getContentType(), a.getNombreOriginal());
    }

    public record Contenido(Path archivo, String contentType, String nombre) {}

    // ------------------------------------------------------------------------------------------------ apoyo

    /** Tipo real por la firma del archivo (JPEG, PNG, WEBP o PDF); null si no coincide con ninguno. */
    static String detectarTipo(byte[] b) {
        if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) return "image/jpeg";
        if (b.length >= 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G' && b[4] == 0x0D && b[5] == 0x0A && b[6] == 0x1A && b[7] == 0x0A) return "image/png";
        if (b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F' && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') return "image/webp";
        if (b.length >= 5 && b[0] == '%' && b[1] == 'P' && b[2] == 'D' && b[3] == 'F' && b[4] == '-') return "application/pdf";
        return null;
    }

    private static String nombreSeguro(String original) {
        String n = original == null ? "adjunto" : original.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        if (n.isEmpty()) n = "adjunto";
        return n.length() > 200 ? n.substring(n.length() - 200) : n;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static AdjuntoResponse aRespuesta(Adjunto a) {
        return new AdjuntoResponse(a.getId(), a.getEntidadTipo(), a.getEntidadId(), a.getNombreOriginal(), a.getContentType(), a.getTamanoBytes(),
                a.getSubidoPor().getNombre(), a.getSubidoEn(), "/api/adjuntos/" + a.getId() + "/contenido");
    }

    private Usuario usuarioAutenticado() {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        return usuarioRepository.findByEmail(email)
                .orElseThrow(() -> new RecursoNoEncontradoException("Usuario autenticado no encontrado: " + email));
    }
}
