package com.planta.acopio.service;

import com.planta.acopio.exception.RecursoNoEncontradoException;
import com.planta.acopio.exception.ValidacionNegocioException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Bloque 3 (confirmado): carga REAL de archivo -- estos tests escriben y leen
// bytes de verdad en un directorio temporal (@TempDir), no mockean el
// filesystem. Cubre exactamente lo que el negocio pidio: nunca una URL falsa.
class ArchivoServiceTest {

    @TempDir
    Path tempDir;

    private ArchivoService service;

    @BeforeEach
    void setUp() {
        service = new ArchivoService(tempDir.toString());
    }

    @Test
    void subeUnaImagenValidaYQuedaRealmenteEscritaEnDisco() throws IOException {
        var archivo = new MockMultipartFile("file", "foto.jpg", "image/jpeg", "contenido-falso-de-imagen".getBytes());

        var respuesta = service.subir(archivo);

        assertThat(respuesta.url()).startsWith("/api/archivos/").endsWith(".jpg");
        assertThat(respuesta.tamanoBytes()).isEqualTo(archivo.getSize());

        Path guardado = tempDir.resolve(respuesta.nombreArchivo());
        assertThat(Files.exists(guardado)).isTrue();
        assertThat(Files.readAllBytes(guardado)).isEqualTo(archivo.getBytes());
    }

    @Test
    void rechazaArchivoVacio() {
        var archivoVacio = new MockMultipartFile("file", "foto.jpg", "image/jpeg", new byte[0]);

        assertThatThrownBy(() -> service.subir(archivoVacio))
                .isInstanceOf(ValidacionNegocioException.class);
    }

    @Test
    void rechazaTipoDeContenidoNoPermitido() {
        var pdf = new MockMultipartFile("file", "documento.pdf", "application/pdf", "contenido".getBytes());

        assertThatThrownBy(() -> service.subir(pdf))
                .isInstanceOf(ValidacionNegocioException.class);
    }

    @Test
    void generaUnNombreUnicoIgnorandoElNombreOriginalDelArchivo() {
        var archivo1 = new MockMultipartFile("file", "../../etc/passwd", "image/png", "a".getBytes());
        var archivo2 = new MockMultipartFile("file", "../../etc/passwd", "image/png", "b".getBytes());

        var respuesta1 = service.subir(archivo1);
        var respuesta2 = service.subir(archivo2);

        // Mismo nombre "original" (malicioso) en ambos -- deben quedar como
        // dos archivos distintos, ninguno fuera del directorio configurado.
        assertThat(respuesta1.nombreArchivo()).isNotEqualTo(respuesta2.nombreArchivo());
        assertThat(respuesta1.nombreArchivo()).doesNotContain("..", "/", "\\");
    }

    @Test
    void resolverDevuelveLaRutaDeUnArchivoExistente() {
        var archivo = new MockMultipartFile("file", "foto.png", "image/png", "contenido".getBytes());
        var respuesta = service.subir(archivo);

        Path resuelto = service.resolver(respuesta.nombreArchivo());

        assertThat(resuelto).exists();
    }

    @Test
    void resolverRechazaUnNombreInexistente() {
        assertThatThrownBy(() -> service.resolver(java.util.UUID.randomUUID() + ".jpg"))
                .isInstanceOf(RecursoNoEncontradoException.class);
    }

    // Defensa en profundidad: aunque nombreArchivo siempre lo genera el
    // servidor al subir, resolver() (usado por el GET) recibe el nombre
    // directamente del cliente -- debe rechazar cualquier intento de escapar
    // del directorio configurado.
    @Test
    void resolverRechazaIntentosDePathTraversal() {
        assertThatThrownBy(() -> service.resolver("../../../etc/passwd"))
                .isInstanceOf(RecursoNoEncontradoException.class);
        assertThatThrownBy(() -> service.resolver("subdir/archivo.jpg"))
                .isInstanceOf(RecursoNoEncontradoException.class);
    }
}
