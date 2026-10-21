package com.planta.acopio.service;

import com.planta.acopio.dto.FamiliaRequest;
import com.planta.acopio.entity.Familia;
import com.planta.acopio.exception.RecursoNoEncontradoException;
import com.planta.acopio.repository.FamiliaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Sort;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

// Fase 1 (Producto + Presentacion): catalogo administrable desde la web, mismo patron CRUD que
// Zona/MotivoObservacion. El usuario autorizado crea familias nuevas sin tocar codigo ni SQL.
@ExtendWith(MockitoExtension.class)
class FamiliaServiceTest {

    @Mock private FamiliaRepository repository;

    private FamiliaService service;
    private final UUID familiaId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new FamiliaService(repository);
    }

    private Familia yogurt() {
        Familia f = new Familia();
        f.setId(familiaId);
        f.setNombre("Yogurt");
        f.setActivo(true);
        return f;
    }

    @Test
    void inventarioIncluyeFamiliasInactivasParaNoOcultarSaldos() {
        Familia inactiva = yogurt();
        inactiva.setActivo(false);
        when(repository.findAll(Sort.by("nombre", "id"))).thenReturn(List.of(inactiva));
        assertThat(service.listarParaInventario()).singleElement().satisfies(familia -> {
            assertThat(familia.id()).isEqualTo(familiaId);
            assertThat(familia.activo()).isFalse();
        });
    }

    @Test
    void crearRegistraUnaFamiliaNueva() {
        when(repository.save(org.mockito.ArgumentMatchers.any())).thenAnswer(inv -> inv.getArgument(0));

        var resultado = service.crear(new FamiliaRequest("Yogurt"));

        assertThat(resultado.nombre()).isEqualTo("Yogurt");
    }

    @Test
    void listarActivasDevuelveSoloLasActivas() {
        when(repository.findByActivoTrue()).thenReturn(List.of(yogurt()));

        var resultado = service.listarActivas();

        assertThat(resultado).hasSize(1);
        assertThat(resultado.get(0).nombre()).isEqualTo("Yogurt");
    }

    @Test
    void actualizarFallaSiLaFamiliaNoExiste() {
        when(repository.findById(familiaId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.actualizar(familiaId, new FamiliaRequest("Otro")))
                .isInstanceOf(RecursoNoEncontradoException.class);
    }

    @Test
    void desactivarYReactivarCambianElEstadoActivo() {
        Familia f = yogurt();
        when(repository.findById(familiaId)).thenReturn(Optional.of(f));

        service.desactivar(familiaId);
        assertThat(f.getActivo()).isFalse();

        var reactivada = service.reactivar(familiaId);
        assertThat(reactivada.activo()).isTrue();
    }
}
