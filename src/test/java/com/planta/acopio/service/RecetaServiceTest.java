package com.planta.acopio.service;

import com.planta.acopio.dto.DisponibilidadProduccionResponse;
import com.planta.acopio.dto.RecetaInsumoRequest;
import com.planta.acopio.dto.RecetaRequest;
import com.planta.acopio.dto.RecetaResponse;
import com.planta.acopio.entity.Insumo;
import com.planta.acopio.entity.Presentacion;
import com.planta.acopio.entity.Producto;
import com.planta.acopio.entity.Receta;
import com.planta.acopio.entity.RecetaInsumo;
import com.planta.acopio.exception.ConflictoNegocioException;
import com.planta.acopio.exception.RecursoInactivoException;
import com.planta.acopio.exception.RecursoNoEncontradoException;
import com.planta.acopio.exception.ValidacionNegocioException;
import com.planta.acopio.repository.InsumoRepository;
import com.planta.acopio.repository.PresentacionRepository;
import com.planta.acopio.repository.ProductoRepository;
import com.planta.acopio.repository.RecetaRepository;
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
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RecetaServiceTest {

    @Mock private RecetaRepository repository;
    @Mock private ProductoRepository productoRepository;
    @Mock private PresentacionRepository presentacionRepository;
    @Mock private InsumoRepository insumoRepository;
    @Mock private InventarioLecheService inventarioLecheService;

    private RecetaService service;
    private Producto andino;
    private Presentacion pieza;
    private Insumo cuajo;
    private Insumo sal;

    @BeforeEach
    void setUp() {
        service = new RecetaService(repository, productoRepository, presentacionRepository, insumoRepository,
                inventarioLecheService);
        andino = new Producto();
        andino.setId(UUID.randomUUID());
        andino.setNombre("Queso Andino");
        andino.setActivo(true);
        pieza = new Presentacion();
        pieza.setId(UUID.randomUUID());
        pieza.setNombre("Pieza");
        pieza.setUnidadConteo("pieza");
        pieza.setProducto(andino);
        pieza.setActivo(true);
        cuajo = insumo("Cuajo", "g", "15");
        sal = insumo("Sal", "kg", "20");
    }

    private Insumo insumo(String nombre, String unidad, String stock) {
        Insumo i = new Insumo();
        i.setId(UUID.randomUUID());
        i.setNombre(nombre);
        i.setUnidadMedida(unidad);
        i.setStock(new BigDecimal(stock));
        i.setActivo(true);
        return i;
    }

    private RecetaRequest requestAndino() {
        return new RecetaRequest("Receta estándar", andino.getId(), pieza.getId(), new BigDecimal("100"), BigDecimal.ONE,
                List.of(new RecetaInsumoRequest(cuajo.getId(), BigDecimal.ONE),
                        new RecetaInsumoRequest(sal.getId(), BigDecimal.ONE)));
    }

    private void stubCatalogoValido() {
        when(productoRepository.findById(andino.getId())).thenReturn(Optional.of(andino));
        when(presentacionRepository.findById(pieza.getId())).thenReturn(Optional.of(pieza));
        lenient().when(insumoRepository.findById(cuajo.getId())).thenReturn(Optional.of(cuajo));
        lenient().when(insumoRepository.findById(sal.getId())).thenReturn(Optional.of(sal));
        lenient().when(repository.save(any())).thenAnswer(inv -> {
            Receta r = inv.getArgument(0);
            if (r.getId() == null) r.setId(UUID.randomUUID());
            return r;
        });
    }

    @Test
    void creaUnaRecetaValidaAsociandoInsumosExistentes() {
        stubCatalogoValido();

        RecetaResponse r = service.crear(requestAndino());

        assertThat(r.productoNombre()).isEqualTo("Queso Andino");
        assertThat(r.presentacionNombre()).isEqualTo("Pieza");
        assertThat(r.litrosBase()).isEqualByComparingTo("100");
        assertThat(r.rendimientoBase()).isEqualByComparingTo("1");
        assertThat(r.insumos()).extracting(RecetaResponse.Insumo::insumoNombre).containsExactly("Cuajo", "Sal");
        assertThat(r.insumos()).extracting(RecetaResponse.Insumo::unidadMedida).containsExactly("g", "kg");
        assertThat(r.activo()).isTrue();
    }

    @Test
    void permiteUnaRecetaSoloDeLeche() {
        stubCatalogoValido();
        var dto = new RecetaRequest("Pasteurizada", andino.getId(), pieza.getId(), new BigDecimal("1"), BigDecimal.ONE, List.of());

        assertThat(service.crear(dto).insumos()).isEmpty();
    }

    @Test
    void rechazaProductoInexistente() {
        when(productoRepository.findById(andino.getId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.crear(requestAndino())).isInstanceOf(RecursoNoEncontradoException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void rechazaProductoDesactivado() {
        andino.setActivo(false);
        when(productoRepository.findById(andino.getId())).thenReturn(Optional.of(andino));

        assertThatThrownBy(() -> service.crear(requestAndino())).isInstanceOf(RecursoInactivoException.class);
    }

    @Test
    void rechazaUnaPresentacionDeOtroProducto() {
        Producto otro = new Producto();
        otro.setId(UUID.randomUUID());
        otro.setNombre("Yogurt");
        pieza.setProducto(otro);
        when(productoRepository.findById(andino.getId())).thenReturn(Optional.of(andino));
        when(presentacionRepository.findById(pieza.getId())).thenReturn(Optional.of(pieza));

        assertThatThrownBy(() -> service.crear(requestAndino()))
                .isInstanceOf(ValidacionNegocioException.class)
                .hasMessageContaining("no pertenece al producto");
    }

    @Test
    void rechazaUnInsumoRepetido() {
        stubCatalogoValido();
        var dto = new RecetaRequest("R", andino.getId(), pieza.getId(), new BigDecimal("100"), BigDecimal.ONE,
                List.of(new RecetaInsumoRequest(cuajo.getId(), BigDecimal.ONE), new RecetaInsumoRequest(cuajo.getId(), BigDecimal.TEN)));

        assertThatThrownBy(() -> service.crear(dto))
                .isInstanceOf(ValidacionNegocioException.class)
                .hasMessageContaining("más de una vez");
    }

    @Test
    void rechazaUnaCantidadDeInsumoNoPositiva() {
        stubCatalogoValido();
        var dto = new RecetaRequest("R", andino.getId(), pieza.getId(), new BigDecimal("100"), BigDecimal.ONE,
                List.of(new RecetaInsumoRequest(cuajo.getId(), BigDecimal.ZERO)));

        assertThatThrownBy(() -> service.crear(dto))
                .isInstanceOf(ValidacionNegocioException.class)
                .hasMessageContaining("mayor que 0");
    }

    @Test
    void rechazaUnInsumoInexistenteODesactivado() {
        stubCatalogoValido();
        UUID fantasma = UUID.randomUUID();
        when(insumoRepository.findById(fantasma)).thenReturn(Optional.empty());
        var dto = new RecetaRequest("R", andino.getId(), pieza.getId(), new BigDecimal("100"), BigDecimal.ONE,
                List.of(new RecetaInsumoRequest(fantasma, BigDecimal.ONE)));
        assertThatThrownBy(() -> service.crear(dto)).isInstanceOf(RecursoNoEncontradoException.class);

        sal.setActivo(false);
        var dto2 = new RecetaRequest("R", andino.getId(), pieza.getId(), new BigDecimal("100"), BigDecimal.ONE,
                List.of(new RecetaInsumoRequest(sal.getId(), BigDecimal.ONE)));
        assertThatThrownBy(() -> service.crear(dto2)).isInstanceOf(RecursoInactivoException.class);
    }

    @Test
    void rechazaNombreRepetidoParaElMismoProducto() {
        when(productoRepository.findById(andino.getId())).thenReturn(Optional.of(andino));
        when(presentacionRepository.findById(pieza.getId())).thenReturn(Optional.of(pieza));
        when(repository.existsByProductoIdAndNombreIgnoreCase(andino.getId(), "Receta estándar")).thenReturn(true);

        assertThatThrownBy(() -> service.crear(requestAndino())).isInstanceOf(ConflictoNegocioException.class);
    }

    private Receta recetaExistente() {
        Receta r = new Receta();
        r.setId(UUID.randomUUID());
        r.setNombre("Receta estándar");
        r.setProducto(andino);
        r.setPresentacion(pieza);
        r.setLitrosBase(new BigDecimal("100"));
        r.setRendimientoBase(BigDecimal.ONE);
        RecetaInsumo c = new RecetaInsumo();
        c.setReceta(r);
        c.setInsumo(cuajo);
        c.setCantidad(BigDecimal.ONE);
        r.getInsumos().add(c);
        return r;
    }

    @Test
    void editarNoPermiteCambiarProductoNiPresentacion() {
        Receta r = recetaExistente();
        when(repository.findById(r.getId())).thenReturn(Optional.of(r));
        var dto = new RecetaRequest("Receta estándar", andino.getId(), UUID.randomUUID(), new BigDecimal("100"),
                BigDecimal.ONE, List.of());

        assertThatThrownBy(() -> service.actualizar(r.getId(), dto))
                .isInstanceOf(ValidacionNegocioException.class)
                .hasMessageContaining("no se pueden cambiar");
    }

    @Test
    void editarActualizaEnSuLugarLaFilaDeUnInsumoQueSeConserva() {
        Receta r = recetaExistente();
        RecetaInsumo filaCuajo = r.getInsumos().get(0);
        when(repository.findById(r.getId())).thenReturn(Optional.of(r));
        when(insumoRepository.findById(cuajo.getId())).thenReturn(Optional.of(cuajo));
        when(insumoRepository.findById(sal.getId())).thenReturn(Optional.of(sal));
        when(repository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        var dto = new RecetaRequest("Receta estándar v2", andino.getId(), pieza.getId(), new BigDecimal("50"),
                new BigDecimal("0.5"), List.of(new RecetaInsumoRequest(cuajo.getId(), new BigDecimal("2")),
                        new RecetaInsumoRequest(sal.getId(), BigDecimal.ONE)));

        RecetaResponse resp = service.actualizar(r.getId(), dto);

        // Misma instancia (UPDATE), no delete+insert que violaría UNIQUE(receta_id, insumo_id).
        assertThat(r.getInsumos()).contains(filaCuajo);
        assertThat(filaCuajo.getCantidad()).isEqualByComparingTo("2");
        assertThat(resp.insumos()).hasSize(2);
        assertThat(resp.nombre()).isEqualTo("Receta estándar v2");
        assertThat(resp.litrosBase()).isEqualByComparingTo("50");
    }

    @Test
    void calcularDevuelveFactorInsumosEsperadoYEstadoDeStock() {
        Receta r = recetaExistente();
        RecetaInsumo s = new RecetaInsumo();
        s.setReceta(r);
        s.setInsumo(sal);
        s.setCantidad(BigDecimal.ONE);
        r.getInsumos().add(s);
        sal.setStock(new BigDecimal("6"));
        when(repository.findById(r.getId())).thenReturn(Optional.of(r));
        when(inventarioLecheService.obtenerSaldoDisponibleLitros()).thenReturn(new BigDecimal("1500"));

        DisponibilidadProduccionResponse c = service.calcular(r.getId(), new BigDecimal("1000"));

        assertThat(c.factor()).isEqualByComparingTo("10");
        assertThat(c.cantidadEsperada()).isEqualByComparingTo("10");
        assertThat(c.leche().requerido()).isEqualByComparingTo("1000");
        assertThat(c.leche().suficiente()).isTrue();
        assertThat(c.insumos()).extracting(l -> l.requerido().stripTrailingZeros().toPlainString())
                .containsExactly("10", "10");
        assertThat(c.insumos().get(1).suficiente()).isFalse();
        assertThat(c.insumos().get(1).faltante()).isEqualByComparingTo("4");
        assertThat(c.todoDisponible()).isFalse();
        // La sal (6 kg) limita a 600 L -> 6 piezas; nunca se ajusta la solicitud automáticamente.
        assertThat(c.litrosMaximos()).isEqualByComparingTo("600");
        assertThat(c.cantidadEsperadaMaxima()).isEqualByComparingTo("6");
        assertThat(c.litrosSolicitados()).isEqualByComparingTo("1000");
    }

    @Test
    void calcularRechazaLitrosNoPositivos() {
        assertThatThrownBy(() -> service.calcular(UUID.randomUUID(), BigDecimal.ZERO))
                .isInstanceOf(ValidacionNegocioException.class);
    }
}
