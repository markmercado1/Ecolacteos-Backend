package com.planta.acopio.service;

import com.planta.acopio.exception.RecursoNoEncontradoException;
import com.planta.acopio.exception.ValidacionNegocioException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.UUID;

// Almacenamiento de las fotos del catalogo (producto/presentacion). Deliberadamente SEPARADO de
// ArchivoService: aquel guarda las fotos de acopio (evidencia interna de proveedores, solo roles internos);
// estas son publicas (las ve cualquier visitante de la web), asi que viven en su propio directorio y se
// sirven por su propio endpoint -- abrir /api/archivos al publico expondria las fotos de acopio.
//
// Validacion en el servidor (no se confia en el Content-Type que declara el cliente): tamano maximo, firma
// real del archivo (JPEG/PNG/WebP) y, para JPEG/PNG, dimensiones leidas de la cabecera (protege contra
// imagenes gigantes que agotan memoria al decodificarse). El archivo se guarda tal cual: el redimensionado y
// la limpieza de metadatos EXIF los hace el panel antes de subir (re-codificar aqui descartaria la
// orientacion EXIF y las fotos de celular quedarian giradas).
//
// Nombre siempre generado por el servidor (UUID + extension): nunca se usa el nombre del cliente.
@Service
public class FotoCatalogoService {

    public static final String RUTA_PUBLICA = "/api/catalogo/fotos/";
    static final long TAMANO_MAXIMO_BYTES = 5L * 1024 * 1024;
    static final int LADO_MAXIMO_PX = 8000;
    static final long PIXELES_MAXIMOS = 40_000_000L;

    private final Path directorio;

    public FotoCatalogoService(@Value("${app.fotos.directorio:uploads/catalogo}") String directorio) {
        this.directorio = Path.of(directorio).toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.directorio);
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo crear el directorio de fotos del catalogo: " + this.directorio, e);
        }
    }

    /** URL publica de una foto guardada, o null si no hay foto. */
    public static String urlDe(String archivo) {
        return archivo == null ? null : RUTA_PUBLICA + archivo;
    }

    /** Valida y guarda la imagen; devuelve el nombre de archivo generado. */
    public String guardar(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ValidacionNegocioException("El archivo está vacío");
        }
        if (file.getSize() > TAMANO_MAXIMO_BYTES) {
            throw new ValidacionNegocioException("La foto supera el tamaño máximo de "
                    + (TAMANO_MAXIMO_BYTES / (1024 * 1024)) + " MB");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo leer el archivo subido", e);
        }
        String extension = extensionPorFirma(bytes);
        if (extension == null) {
            throw new ValidacionNegocioException("Formato no permitido: solo se aceptan imágenes JPEG, PNG o WebP");
        }
        if (!extension.equals(".webp")) {
            validarDimensiones(bytes);
        }

        String nombre = UUID.randomUUID() + extension;
        try {
            Files.write(directorio.resolve(nombre), bytes);
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo guardar la foto", e);
        }
        return nombre;
    }

    /** Ruta del archivo si existe dentro del directorio del catalogo; 404 en cualquier otro caso. */
    public Path resolver(String nombre) {
        if (nombre == null || nombre.isBlank() || nombre.contains("..") || nombre.contains("/") || nombre.contains("\\")) {
            throw new RecursoNoEncontradoException("Foto no encontrada");
        }
        Path archivo = directorio.resolve(nombre).normalize();
        if (!archivo.startsWith(directorio) || !Files.isRegularFile(archivo)) {
            throw new RecursoNoEncontradoException("Foto no encontrada");
        }
        return archivo;
    }

    /** Borra el archivo si existe; un fallo de borrado nunca debe tumbar la operacion de negocio. */
    public void eliminar(String nombre) {
        if (nombre == null) return;
        try {
            Files.deleteIfExists(resolverSinExistencia(nombre));
        } catch (IOException | RuntimeException ignorado) {
            // archivo huerfano: inofensivo, se puede limpiar a mano
        }
    }

    /** Borra el archivo viejo solo si la transaccion de BD que lo desreferencia se confirma. */
    public void eliminarAlConfirmar(String nombre) {
        if (nombre == null) return;
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            eliminar(nombre);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                eliminar(nombre);
            }
        });
    }

    /** Borra el archivo recien subido si la transaccion de BD se revierte (evita huerfanos). */
    public void eliminarAlRevertir(String nombre) {
        if (nombre == null || !TransactionSynchronizationManager.isSynchronizationActive()) return;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    eliminar(nombre);
                }
            }
        });
    }

    private Path resolverSinExistencia(String nombre) {
        Path archivo = directorio.resolve(nombre).normalize();
        if (!archivo.startsWith(directorio)) {
            throw new IllegalArgumentException("Nombre fuera del directorio de fotos");
        }
        return archivo;
    }

    private static String extensionPorFirma(byte[] b) {
        if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
            return ".jpg";
        }
        if (b.length >= 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G'
                && b[4] == 0x0D && b[5] == 0x0A && b[6] == 0x1A && b[7] == 0x0A) {
            return ".png";
        }
        if (b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') {
            return ".webp";
        }
        return null;
    }

    private static void validarDimensiones(byte[] bytes) {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> lectores = ImageIO.getImageReaders(in);
            if (!lectores.hasNext()) {
                throw new ValidacionNegocioException("La imagen está dañada o no se puede leer");
            }
            ImageReader lector = lectores.next();
            try {
                lector.setInput(in);
                int ancho = lector.getWidth(0);
                int alto = lector.getHeight(0);
                if (ancho <= 0 || alto <= 0 || ancho > LADO_MAXIMO_PX || alto > LADO_MAXIMO_PX
                        || (long) ancho * alto > PIXELES_MAXIMOS) {
                    throw new ValidacionNegocioException("La imagen es demasiado grande en píxeles (máximo "
                            + LADO_MAXIMO_PX + " px por lado)");
                }
            } finally {
                lector.dispose();
            }
        } catch (IOException | RuntimeException e) {
            if (e instanceof ValidacionNegocioException v) throw v;
            throw new ValidacionNegocioException("La imagen está dañada o no se puede leer");
        }
    }
}
