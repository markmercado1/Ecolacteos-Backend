package com.planta.acopio.service;

import com.planta.acopio.dto.PresentacionRequest;
import com.planta.acopio.entity.Presentacion;
import com.planta.acopio.entity.Producto;
import com.planta.acopio.exception.ConflictoNegocioException;
import com.planta.acopio.exception.RecursoNoEncontradoException;
import com.planta.acopio.exception.ValidacionNegocioException;
import com.planta.acopio.repository.PresentacionRepository;
import com.planta.acopio.repository.ProductoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// Fase 1 (Producto + Presentacion): se administra DENTRO de la ficha del producto. Crear una presentacion
// NO genera stock ni precio -- en esta fase ninguna otra tabla referencia todavia presentacion_id.
@ExtendWith(MockitoExtension.class)
class PresentacionServiceTest {

    @Mock private PresentacionRepository repository;
    @Mock private ProductoRepository productoRepository;
    @Mock private FotoCatalogoService fotos;

    private PresentacionService service;
    private final UUID productoId = UUID.randomUUID();
    private final UUID otroProductoId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new PresentacionService(repository, productoRepository, fotos);
    }

    private Producto yogurt() {
        Producto p = new Producto();
        p.setId(productoId);
        p.setNombre("Yogurt de fresa");
        return p;
    }

    @Test
    void crearRegistraUnaPresentacionConContenidoDeclarado() {
        when(productoRepository.findById(productoId)).thenReturn(Optional.of(yogurt()));
        when(repository.existsByProductoIdAndNombre(productoId, "Botella 1 L")).thenReturn(false);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var dto = new PresentacionRequest("Botella 1 L", BigDecimal.ONE, "L", "botella");
        var resultado = service.crear(productoId, dto);

        assertThat(resultado.nombre()).isEqualTo("Botella 1 L");
        assertThat(resultado.contenidoCantidad()).isEqualByComparingTo("1");
        assertThat(resultado.contenidoUnidad()).isEqualTo("L");
        assertThat(resultado.unidadConteo()).isEqualTo("botella");
    }

    // Correccion explicita del usuario: una "Pieza" sin peso/volumen declarado no debe inventar ningun
    // valor -- contenidoCantidad/contenidoUnidad quedan null.
    @Test
    void crearUnaPiezaSinContenidoDeclaradoNoInventaNingunValor() {
        when(productoRepository.findById(productoId)).thenReturn(Optional.of(yogurt()));
        when(repository.existsByProductoIdAndNombre(any(), any())).thenReturn(false);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var dto = new PresentacionRequest("Pieza", null, null, "pieza");
        var resultado = service.crear(productoId, dto);

        assertThat(resultado.contenidoCantidad()).isNull();
        assertThat(resultado.contenidoUnidad()).isNull();
    }

    @Test
    void crearRechazaUnNombreDuplicadoParaElMismoProducto() {
        when(productoRepository.findById(productoId)).thenReturn(Optional.of(yogurt()));
        when(repository.existsByProductoIdAndNombre(productoId, "Botella 1 L")).thenReturn(true);

        var dto = new PresentacionRequest("Botella 1 L", BigDecimal.ONE, "L", "botella");

        assertThatThrownBy(() -> service.crear(productoId, dto)).isInstanceOf(ConflictoNegocioException.class);
    }

    @Test
    void crearFallaSiElProductoNoExiste() {
        when(productoRepository.findById(productoId)).thenReturn(Optional.empty());

        var dto = new PresentacionRequest("Botella 1 L", BigDecimal.ONE, "L", "botella");

        assertThatThrownBy(() -> service.crear(productoId, dto)).isInstanceOf(RecursoNoEncontradoException.class);
    }

    // Defensivo: una presentacion no puede editarse/desactivarse a traves del id de OTRO producto distinto
    // al que realmente pertenece (mismatch entre el productoId de la URL y el real).
    @Test
    void actualizarFallaSiLaPresentacionNoPerteneceAlProductoIndicado() {
        Presentacion p = new Presentacion();
        p.setId(UUID.randomUUID());
        p.setProducto(yogurt());
        when(repository.findById(p.getId())).thenReturn(Optional.of(p));

        var dto = new PresentacionRequest("Botella 2 L", BigDecimal.valueOf(2), "L", "botella");

        assertThatThrownBy(() -> service.actualizar(otroProductoId, p.getId(), dto))
                .isInstanceOf(RecursoNoEncontradoException.class);
    }

    @Test
    void desactivarYReactivarCambianElEstadoActivo() {
        Presentacion p = new Presentacion();
        p.setId(UUID.randomUUID());
        p.setProducto(yogurt());
        p.setActivo(true);
        when(repository.findById(p.getId())).thenReturn(Optional.of(p));

        service.desactivar(productoId, p.getId());
        assertThat(p.getActivo()).isFalse();

        var reactivada = service.reactivar(productoId, p.getId());
        assertThat(reactivada.activo()).isTrue();
    }


    @Test
    void crearRechazaUnContenidoSinUnidad() {
        when(productoRepository.findById(productoId)).thenReturn(Optional.of(yogurt()));

        var dto = new PresentacionRequest("Botella", BigDecimal.valueOf(5), null, "botella");

        assertThatThrownBy(() -> service.crear(productoId, dto)).isInstanceOf(ValidacionNegocioException.class);
    }

    @Test
    void crearRechazaUnaUnidadSinContenido() {
        when(productoRepository.findById(productoId)).thenReturn(Optional.of(yogurt()));

        var dto = new PresentacionRequest("Botella", null, "L", "botella");

        assertThatThrownBy(() -> service.crear(productoId, dto)).isInstanceOf(ValidacionNegocioException.class);
    }

    @Test
    void actualizarRechazaUnContenidoSinUnidad() {
        Presentacion p = new Presentacion();
        p.setId(UUID.randomUUID());
        p.setProducto(yogurt());
        when(repository.findById(p.getId())).thenReturn(Optional.of(p));

        var dto = new PresentacionRequest("Botella", BigDecimal.valueOf(5), null, "botella");

        assertThatThrownBy(() -> service.actualizar(productoId, p.getId(), dto))
                .isInstanceOf(ValidacionNegocioException.class);
    }

    @Test
    void crearGuardaNombreYUnidadDeConteoSinEspaciosSobrantes() {
        when(productoRepository.findById(productoId)).thenReturn(Optional.of(yogurt()));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var resultado = service.crear(productoId, new PresentacionRequest("  Pieza ", null, null, " pieza "));

        assertThat(resultado.nombre()).isEqualTo("Pieza");
        assertThat(resultado.unidadConteo()).isEqualTo("pieza");
    }

    @Test
    void reemplazarFotoGuardaLaNuevaYProgramaElBorradoDeLaVieja() {
        Presentacion p = new Presentacion();
        p.setId(UUID.randomUUID());
        p.setProducto(yogurt());
        p.setFotoArchivo("vieja.jpg");
        var archivo = new org.springframework.mock.web.MockMultipartFile("file", "x.jpg", "image/jpeg", new byte[]{1});
        when(repository.findById(p.getId())).thenReturn(Optional.of(p));
        when(fotos.guardar(archivo)).thenReturn("nueva.jpg");

        var resultado = service.reemplazarFoto(productoId, p.getId(), archivo);

        assertThat(resultado.fotoUrl()).isEqualTo("/api/catalogo/fotos/nueva.jpg");
        verify(fotos).eliminarAlConfirmar("vieja.jpg");
        verify(fotos).eliminarAlRevertir("nueva.jpg");
    }

    @Test
    void reemplazarFotoFallaSiLaPresentacionPerteneceAOtroProducto() {
        Presentacion p = new Presentacion();
        p.setId(UUID.randomUUID());
        p.setProducto(yogurt());
        var archivo = new org.springframework.mock.web.MockMultipartFile("file", "x.jpg", "image/jpeg", new byte[]{1});
        when(repository.findById(p.getId())).thenReturn(Optional.of(p));

        assertThatThrownBy(() -> service.reemplazarFoto(otroProductoId, p.getId(), archivo))
                .isInstanceOf(RecursoNoEncontradoException.class);
        verify(fotos, never()).guardar(any());
    }

    @Test
    void quitarFotoLimpiaLaReferencia() {
        Presentacion p = new Presentacion();
        p.setId(UUID.randomUUID());
        p.setProducto(yogurt());
        p.setFotoArchivo("vieja.jpg");
        when(repository.findById(p.getId())).thenReturn(Optional.of(p));

        var resultado = service.quitarFoto(productoId, p.getId());

        assertThat(resultado.fotoUrl()).isNull();
        verify(fotos).eliminarAlConfirmar("vieja.jpg");
    }

    @Test
    void unaPresentacionNuevaDeUnProductoControladoNaceConSaldoInicialCero() {
        when(productoRepository.findById(productoId)).thenReturn(Optional.of(yogurt()));
        when(productoRepository.controlaVencimiento(productoId)).thenReturn(Optional.of(true));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        var captor = org.mockito.ArgumentCaptor.forClass(Presentacion.class);

        service.crear(productoId, new PresentacionRequest("Pieza", null, null, "pieza"));

        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getSaldoSinTrazabilidadInicial()).isEqualTo(0);
    }

    @Test
    void unaPresentacionNuevaDeUnProductoSinControlNoTocaElSaldoInicial() {
        when(productoRepository.findById(productoId)).thenReturn(Optional.of(yogurt()));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        var captor = org.mockito.ArgumentCaptor.forClass(Presentacion.class);

        service.crear(productoId, new PresentacionRequest("Pieza", null, null, "pieza"));

        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getSaldoSinTrazabilidadInicial()).isNull();
    }
}
