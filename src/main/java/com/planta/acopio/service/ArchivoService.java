package com.planta.acopio.service;

import com.planta.acopio.dto.ArchivoResponse;
import com.planta.acopio.exception.RecursoNoEncontradoException;
import com.planta.acopio.exception.ValidacionNegocioException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;

// Bloque 3 (confirmado): almacenamiento LOCAL EN DISCO -- sin agregar ninguna
// dependencia externa (S3/Cloudinary/MinIO descartados a proposito). Spring
// Boot ya soporta multipart via Servlet (spring-boot-starter-web), no hace
// falta ninguna libreria nueva. Carga real de archivo: se escriben los bytes
// reales a disco y se sirven de vuelta tal cual, nunca una URL inventada.
//
// DECISION DE INFRAESTRUCTURA PENDIENTE (no resuelta aqui, requiere
// confirmacion para produccion): este directorio debe vivir en un volumen
// persistente si el servidor corre en un contenedor efimero -- si el
// filesystem no sobrevive un redeploy, las fotos se pierden. Configurable
// via ARCHIVOS_DIR (default "uploads", relativo al working directory).
@Service
public class ArchivoService {

    private static final Set<String> CONTENT_TYPES_PERMITIDOS = Set.of("image/jpeg", "image/png", "image/webp");

    private final Path directorio;

    public ArchivoService(@Value("${app.archivos.directorio:uploads}") String directorio) {
        this.directorio = Path.of(directorio).toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.directorio);
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo crear el directorio de archivos: " + this.directorio, e);
        }
    }

    public ArchivoResponse subir(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ValidacionNegocioException("El archivo esta vacio");
        }
        String contentType = file.getContentType();
        if (contentType == null || !CONTENT_TYPES_PERMITIDOS.contains(contentType)) {
            throw new ValidacionNegocioException(
                    "Tipo de archivo no permitido (" + contentType + "); solo se aceptan: " + CONTENT_TYPES_PERMITIDOS);
        }

        // Nombre siempre generado por el servidor (UUID) -- nunca se usa el
        // nombre original del archivo para construir la ruta, evita cualquier
        // path traversal desde el nombre que mande el cliente.
        String nombreArchivo = UUID.randomUUID() + extensionDe(contentType);
        Path destino = directorio.resolve(nombreArchivo);

        try {
            file.transferTo(destino);
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo guardar el archivo", e);
        }

        return new ArchivoResponse("/api/archivos/" + nombreArchivo, nombreArchivo, file.getSize());
    }

    public Path resolver(String nombreArchivo) {
        if (nombreArchivo == null || nombreArchivo.isBlank()
                || nombreArchivo.contains("..") || nombreArchivo.contains("/") || nombreArchivo.contains("\\")) {
            throw new RecursoNoEncontradoException("Archivo no encontrado: " + nombreArchivo);
        }
        Path archivo = directorio.resolve(nombreArchivo).normalize();
        if (!archivo.startsWith(directorio) || !Files.exists(archivo) || !Files.isRegularFile(archivo)) {
            throw new RecursoNoEncontradoException("Archivo no encontrado: " + nombreArchivo);
        }
        return archivo;
    }

    private String extensionDe(String contentType) {
        return switch (contentType) {
            case "image/jpeg" -> ".jpg";
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            default -> "";
        };
    }
}
