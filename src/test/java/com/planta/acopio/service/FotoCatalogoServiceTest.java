package com.planta.acopio.service;

import com.planta.acopio.exception.RecursoNoEncontradoException;
import com.planta.acopio.exception.ValidacionNegocioException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FotoCatalogoServiceTest {

    @TempDir Path tmp;
    private FotoCatalogoService service;

    @BeforeEach
    void setUp() {
        service = new FotoCatalogoService(tmp.resolve("catalogo").toString());
    }

    private static byte[] imagen(String formato, int ancho, int alto) throws Exception {
        BufferedImage img = new BufferedImage(ancho, alto, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, formato, out);
        return out.toByteArray();
    }

    private static MockMultipartFile archivo(byte[] bytes, String tipoDeclarado) {
        return new MockMultipartFile("file", "cualquier-nombre.bin", tipoDeclarado, bytes);
    }

    @Test
    void guardaUnJpegValidoConNombreGeneradoPorElServidor() throws Exception {
        String nombre = service.guardar(archivo(imagen("jpg", 40, 30), "image/jpeg"));

        assertThat(nombre).matches("[0-9a-f-]{36}\\.jpg");
        assertThat(service.resolver(nombre)).exists();
        assertThat(FotoCatalogoService.urlDe(nombre)).isEqualTo("/api/catalogo/fotos/" + nombre);
    }

    @Test
    void guardaUnPngValido() throws Exception {
        String nombre = service.guardar(archivo(imagen("png", 40, 30), "image/png"));

        assertThat(nombre).endsWith(".png");
    }

    @Test
    void aceptaUnWebpPorSuFirmaAunqueNoSePuedaDecodificar() {
        byte[] webp = "RIFF\0\0\0\0WEBPVP8 ".getBytes(StandardCharsets.ISO_8859_1);

        assertThat(service.guardar(archivo(webp, "image/webp"))).endsWith(".webp");
    }

    @Test
    void laExtensionSalDeLaFirmaRealNoDelTipoQueDeclaraElCliente() throws Exception {
        String nombre = service.guardar(archivo(imagen("png", 10, 10), "image/jpeg"));

        assertThat(nombre).endsWith(".png");
    }

    @Test
    void rechazaUnArchivoVacio() {
        assertThatThrownBy(() -> service.guardar(archivo(new byte[0], "image/jpeg")))
                .isInstanceOf(ValidacionNegocioException.class).hasMessageContaining("vacío");
    }

    @Test
    void rechazaUnArchivoQueDiceSerImagenPeroNoLoEs() {
        byte[] texto = "<html><script>alert(1)</script></html>".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> service.guardar(archivo(texto, "image/jpeg")))
                .isInstanceOf(ValidacionNegocioException.class).hasMessageContaining("Formato no permitido");
    }

    @Test
    void rechazaUnaFotoMasGrandeQueElLimite() {
        byte[] enorme = new byte[(int) FotoCatalogoService.TAMANO_MAXIMO_BYTES + 1];
        enorme[0] = (byte) 0xFF; enorme[1] = (byte) 0xD8; enorme[2] = (byte) 0xFF;

        assertThatThrownBy(() -> service.guardar(archivo(enorme, "image/jpeg")))
                .isInstanceOf(ValidacionNegocioException.class).hasMessageContaining("tamaño máximo");
    }

    @Test
    void rechazaUnaImagenConFirmaPeroCuerpoCorrupto() {
        byte[] corrupto = new byte[64];
        corrupto[0] = (byte) 0x89; corrupto[1] = 'P'; corrupto[2] = 'N'; corrupto[3] = 'G';
        corrupto[4] = 0x0D; corrupto[5] = 0x0A; corrupto[6] = 0x1A; corrupto[7] = 0x0A;

        assertThatThrownBy(() -> service.guardar(archivo(corrupto, "image/png")))
                .isInstanceOf(ValidacionNegocioException.class).hasMessageContaining("dañada");
    }

    @Test
    void rechazaUnaImagenDemasiadoGrandeEnPixeles() throws Exception {
        byte[] ancha = imagen("png", FotoCatalogoService.LADO_MAXIMO_PX + 1, 1);

        assertThatThrownBy(() -> service.guardar(archivo(ancha, "image/png")))
                .isInstanceOf(ValidacionNegocioException.class).hasMessageContaining("píxeles");
    }

    @Test
    void resolverRechazaRecorridosDeDirectorioYNombresInexistentes() {
        assertThatThrownBy(() -> service.resolver("../secreto.jpg")).isInstanceOf(RecursoNoEncontradoException.class);
        assertThatThrownBy(() -> service.resolver("a/b.jpg")).isInstanceOf(RecursoNoEncontradoException.class);
        assertThatThrownBy(() -> service.resolver("a\\b.jpg")).isInstanceOf(RecursoNoEncontradoException.class);
        assertThatThrownBy(() -> service.resolver("no-existe.jpg")).isInstanceOf(RecursoNoEncontradoException.class);
        assertThatThrownBy(() -> service.resolver(" ")).isInstanceOf(RecursoNoEncontradoException.class);
    }

    @Test
    void eliminarBorraElArchivoYToleraNulosEInexistentes() throws Exception {
        String nombre = service.guardar(archivo(imagen("jpg", 10, 10), "image/jpeg"));

        service.eliminar(nombre);
        assertThat(Files.exists(tmp.resolve("catalogo").resolve(nombre))).isFalse();

        service.eliminar(nombre);
        service.eliminar(null);
        service.eliminar("../fuera.jpg"); // nunca debe lanzar ni tocar nada fuera del directorio
    }

    @Test
    void sinTransaccionActivaEliminarAlConfirmarBorraDeInmediato() throws Exception {
        String nombre = service.guardar(archivo(imagen("jpg", 10, 10), "image/jpeg"));

        service.eliminarAlConfirmar(nombre);

        assertThat(Files.exists(tmp.resolve("catalogo").resolve(nombre))).isFalse();
    }

    @Test
    void urlDeUnaFotoNulaEsNula() {
        assertThat(FotoCatalogoService.urlDe(null)).isNull();
    }
}
