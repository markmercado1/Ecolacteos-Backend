package com.planta.acopio.service;

import com.planta.acopio.dto.ProductoRequest;
import com.planta.acopio.entity.CicloCapital;
import com.planta.acopio.entity.Familia;
import com.planta.acopio.entity.Producto;
import com.planta.acopio.exception.ConflictoNegocioException;
import com.planta.acopio.exception.RecursoNoEncontradoException;
import com.planta.acopio.repository.FamiliaRepository;
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

// Fase 1 (Producto + Presentacion): generaliza TipoQuesoService. rendimientoEsperadoPct/cicloCapital dejan
// de ser obligatorios -- un producto de otra familia se crea sin ellos, sin inventar un valor.
@ExtendWith(MockitoExtension.class)
class ProductoServiceTest {

    @Mock private ProductoRepository repository;
    @Mock private FamiliaRepository familiaRepository;
    @Mock private PresentacionRepository presentacionRepository;
    @Mock private FotoCatalogoService fotos;

    private ProductoService service;
    private final UUID familiaQuesoId = UUID.randomUUID();
    private final UUID familiaYogurtId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new ProductoService(repository, familiaRepository, presentacionRepository, fotos);
    }

    private Familia familia(UUID id, String nombre) {
        Familia f = new Familia();
        f.setId(id);
        f.setNombre(nombre);
        return f;
    }

    @Test
    void crearUnQuesoConservaRendimientoEsperadoYCicloCapital() {
        when(familiaRepository.findById(familiaQuesoId)).thenReturn(Optional.of(familia(familiaQuesoId, "Queso")));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(presentacionRepository.findByProductoIdOrderByNombre(any())).thenReturn(List.of());

        var dto = new ProductoRequest("Queso Fresco", familiaQuesoId, BigDecimal.valueOf(12), "RAPIDO");
        var resultado = service.crear(dto);

        assertThat(resultado.nombre()).isEqualTo("Queso Fresco");
        assertThat(resultado.familiaNombre()).isEqualTo("Queso");
        assertThat(resultado.rendimientoEsperadoPct()).isEqualByComparingTo("12");
        assertThat(resultado.cicloCapital()).isEqualTo("RAPIDO");
        assertThat(resultado.presentaciones()).isEmpty();
    }

    // Correccion explicita del usuario: un producto de otra familia se crea SIN rendimiento esperado ni
    // ciclo de capital -- no se inventa una equivalencia que el negocio no definio.
    @Test
    void creaUnProductoDeOtraFamiliaSinRendimientoEsperadoNiCicloCapital() {
        when(familiaRepository.findById(familiaYogurtId)).thenReturn(Optional.of(familia(familiaYogurtId, "Yogurt")));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(presentacionRepository.findByProductoIdOrderByNombre(any())).thenReturn(List.of());

        var dto = new ProductoRequest("Yogurt de fresa", familiaYogurtId, null, null);
        var resultado = service.crear(dto);

        assertThat(resultado.familiaNombre()).isEqualTo("Yogurt");
        assertThat(resultado.rendimientoEsperadoPct()).isNull();
        assertThat(resultado.cicloCapital()).isNull();

        var entidadGuardada = org.mockito.ArgumentCaptor.forClass(Producto.class);
        org.mockito.Mockito.verify(repository).save(entidadGuardada.capture());
        assertThat(entidadGuardada.getValue().getCicloCapital()).isNull();
    }

    @Test
    void crearFallaSiLaFamiliaNoExiste() {
        when(familiaRepository.findById(familiaYogurtId)).thenReturn(Optional.empty());

        var dto = new ProductoRequest("Yogurt de fresa", familiaYogurtId, null, null);

        assertThatThrownBy(() -> service.crear(dto)).isInstanceOf(RecursoNoEncontradoException.class);
    }

    @Test
    void crearFallaSiElCicloCapitalNoEsUnValorLegalDelEnum() {
        when(familiaRepository.findById(familiaQuesoId)).thenReturn(Optional.of(familia(familiaQuesoId, "Queso")));

        var dto = new ProductoRequest("Queso Fresco", familiaQuesoId, BigDecimal.TEN, "NO_EXISTE");

        assertThatThrownBy(() -> service.crear(dto)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void listarActivosIncluyeLasPresentacionesDeCadaProducto() {
        Producto queso = new Producto();
        queso.setId(UUID.randomUUID());
        queso.setNombre("Queso Fresco");
        queso.setFamilia(familia(familiaQuesoId, "Queso"));
        queso.setActivo(true);
        queso.setCicloCapital(CicloCapital.RAPIDO);
        when(repository.findByActivoTrue(OrdenCatalogo.POR_NOMBRE)).thenReturn(List.of(queso));
        when(presentacionRepository.findByProductoIdOrderByNombre(queso.getId())).thenReturn(List.of());

        var resultado = service.listarActivos();

        assertThat(resultado).hasSize(1);
        assertThat(resultado.get(0).presentaciones()).isNotNull();
    }

    @Test
    void crearRechazaUnNombreDuplicadoSinImportarMayusculasNiEspacios() {
        when(familiaRepository.findById(familiaQuesoId)).thenReturn(Optional.of(familia(familiaQuesoId, "Queso")));
        when(repository.existsByNombreIgnoreCase("Queso Fresco")).thenReturn(true);

        var dto = new ProductoRequest("  Queso Fresco  ", familiaQuesoId, null, null);

        assertThatThrownBy(() -> service.crear(dto)).isInstanceOf(ConflictoNegocioException.class)
                .hasMessageContaining("Queso Fresco");
        verify(repository, never()).save(any());
    }

    @Test
    void crearGuardaElNombreSinEspaciosSobrantes() {
        when(familiaRepository.findById(familiaYogurtId)).thenReturn(Optional.of(familia(familiaYogurtId, "Yogurt")));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(presentacionRepository.findByProductoIdOrderByNombre(any())).thenReturn(List.of());

        var resultado = service.crear(new ProductoRequest("  Yogurt de fresa ", familiaYogurtId, null, null));

        assertThat(resultado.nombre()).isEqualTo("Yogurt de fresa");
    }

    @Test
    void actualizarRechazaElNombreDeOtroProducto() {
        UUID id = UUID.randomUUID();
        Producto existente = new Producto();
        existente.setId(id);
        existente.setNombre("Queso Andino");
        existente.setFamilia(familia(familiaQuesoId, "Queso"));
        when(repository.findById(id)).thenReturn(Optional.of(existente));
        when(familiaRepository.findById(familiaQuesoId)).thenReturn(Optional.of(familia(familiaQuesoId, "Queso")));
        when(repository.existsByNombreIgnoreCaseAndIdNot("Queso Fresco", id)).thenReturn(true);

        var dto = new ProductoRequest("Queso Fresco", familiaQuesoId, null, null);

        assertThatThrownBy(() -> service.actualizar(id, dto)).isInstanceOf(ConflictoNegocioException.class);
        assertThat(existente.getNombre()).isEqualTo("Queso Andino");
    }

    @Test
    void actualizarPermiteConservarElPropioNombre() {
        UUID id = UUID.randomUUID();
        Producto existente = new Producto();
        existente.setId(id);
        existente.setNombre("Queso Andino");
        existente.setFamilia(familia(familiaQuesoId, "Queso"));
        when(repository.findById(id)).thenReturn(Optional.of(existente));
        when(familiaRepository.findById(familiaQuesoId)).thenReturn(Optional.of(familia(familiaQuesoId, "Queso")));
        when(presentacionRepository.findByProductoIdOrderByNombre(id)).thenReturn(List.of());

        var resultado = service.actualizar(id, new ProductoRequest("Queso Andino", familiaQuesoId, BigDecimal.TEN, "RAPIDO"));

        assertThat(resultado.nombre()).isEqualTo("Queso Andino");
        assertThat(resultado.cicloCapital()).isEqualTo("RAPIDO");
    }

    @Test
    void desactivarYReactivarCambianElEstadoActivo() {
        UUID id = UUID.randomUUID();
        Producto p = new Producto();
        p.setId(id);
        p.setNombre("Queso Andino");
        p.setFamilia(familia(familiaQuesoId, "Queso"));
        when(repository.findById(id)).thenReturn(Optional.of(p));
        when(presentacionRepository.findByProductoIdOrderByNombre(id)).thenReturn(List.of());

        service.desactivar(id);
        assertThat(p.getActivo()).isFalse();
        assertThat(service.reactivar(id).activo()).isTrue();
    }

    @Test
    void desactivarFallaSiElProductoNoExiste() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.desactivar(id)).isInstanceOf(RecursoNoEncontradoException.class);
    }

    private Producto productoConFoto(UUID id, String foto) {
        Producto p = new Producto();
        p.setId(id);
        p.setNombre("Queso Andino");
        p.setFamilia(familia(familiaQuesoId, "Queso"));
        p.setFotoArchivo(foto);
        return p;
    }

    @Test
    void reemplazarFotoGuardaLaNuevaYProgramaElBorradoDeLaVieja() {
        UUID id = UUID.randomUUID();
        Producto p = productoConFoto(id, "vieja.jpg");
        var archivo = new org.springframework.mock.web.MockMultipartFile("file", "x.jpg", "image/jpeg", new byte[]{1});
        when(repository.findById(id)).thenReturn(Optional.of(p));
        when(fotos.guardar(archivo)).thenReturn("nueva.jpg");
        when(presentacionRepository.findByProductoIdOrderByNombre(id)).thenReturn(List.of());

        var resultado = service.reemplazarFoto(id, archivo);

        assertThat(resultado.fotoUrl()).isEqualTo("/api/catalogo/fotos/nueva.jpg");
        assertThat(p.getFotoArchivo()).isEqualTo("nueva.jpg");
        verify(fotos).eliminarAlConfirmar("vieja.jpg");
        verify(fotos).eliminarAlRevertir("nueva.jpg");
    }

    @Test
    void reemplazarFotoDeUnProductoInexistenteNoGuardaNada() {
        UUID id = UUID.randomUUID();
        var archivo = new org.springframework.mock.web.MockMultipartFile("file", "x.jpg", "image/jpeg", new byte[]{1});
        when(repository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.reemplazarFoto(id, archivo)).isInstanceOf(RecursoNoEncontradoException.class);
        verify(fotos, never()).guardar(any());
    }

    @Test
    void quitarFotoLimpiaLaReferenciaYProgramaElBorrado() {
        UUID id = UUID.randomUUID();
        Producto p = productoConFoto(id, "vieja.jpg");
        when(repository.findById(id)).thenReturn(Optional.of(p));
        when(presentacionRepository.findByProductoIdOrderByNombre(id)).thenReturn(List.of());

        var resultado = service.quitarFoto(id);

        assertThat(resultado.fotoUrl()).isNull();
        assertThat(p.getFotoArchivo()).isNull();
        verify(fotos).eliminarAlConfirmar("vieja.jpg");
    }

    @Test
    void laRespuestaIncluyeLaUrlPublicaDeLaFotoDelProductoYDeSusPresentaciones() {
        UUID id = UUID.randomUUID();
        Producto p = productoConFoto(id, "prod.jpg");
        com.planta.acopio.entity.Presentacion pr = new com.planta.acopio.entity.Presentacion();
        pr.setId(UUID.randomUUID());
        pr.setProducto(p);
        pr.setNombre("Pieza");
        pr.setUnidadConteo("pieza");
        pr.setFotoArchivo("pres.jpg");
        when(repository.findByActivoTrue(OrdenCatalogo.POR_NOMBRE)).thenReturn(List.of(p));
        when(presentacionRepository.findByProductoIdOrderByNombre(id)).thenReturn(List.of(pr));

        var resultado = service.listarActivos().get(0);

        assertThat(resultado.fotoUrl()).isEqualTo("/api/catalogo/fotos/prod.jpg");
        assertThat(resultado.presentaciones().get(0).fotoUrl()).isEqualTo("/api/catalogo/fotos/pres.jpg");
    }
}
