package com.planta.acopio.service;

import com.planta.acopio.dto.DefinirRutaZonaRequest;
import com.planta.acopio.dto.RutaItemRequest;
import com.planta.acopio.entity.Proveedor;
import com.planta.acopio.entity.RutaProveedorOrden;
import com.planta.acopio.entity.Zona;
import com.planta.acopio.exception.RecursoNoEncontradoException;
import com.planta.acopio.exception.ValidacionNegocioException;
import com.planta.acopio.repository.ProveedorRepository;
import com.planta.acopio.repository.RutaProveedorOrdenRepository;
import com.planta.acopio.repository.ZonaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

// RF-RUT-04: RutaZonaService.definirRuta reemplaza la ruta COMPLETA de una
// zona en cada llamada (borra + inserta), y solo acepta proveedores
// actualmente asignados a esa zona -- ver comentario en el service.
@ExtendWith(MockitoExtension.class)
class RutaZonaServiceTest {

    @Mock private RutaProveedorOrdenRepository repository;
    @Mock private ZonaRepository zonaRepository;
    @Mock private ProveedorRepository proveedorRepository;

    private RutaZonaService service;
    private final UUID zonaId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new RutaZonaService(repository, zonaRepository, proveedorRepository);
    }

    @Test
    void rechazaOrdenRepetidoDentroDelMismoRequest() {
        Zona zona = zonaConId(zonaId);
        when(zonaRepository.findById(zonaId)).thenReturn(Optional.of(zona));

        Proveedor p1 = proveedorEnZona(zona);
        when(proveedorRepository.findById(p1.getId())).thenReturn(Optional.of(p1));

        var item1 = new RutaItemRequest(p1.getId(), 1, null);
        var item2 = new RutaItemRequest(UUID.randomUUID(), 1, null); // mismo orden

        assertThatThrownBy(() -> service.definirRuta(zonaId, new DefinirRutaZonaRequest(List.of(item1, item2))))
                .isInstanceOf(ValidacionNegocioException.class);

        verify(repository, never()).deleteByZonaId(any());
    }

    @Test
    void rechazaProveedorRepetidoDentroDelMismoRequest() {
        Zona zona = zonaConId(zonaId);
        when(zonaRepository.findById(zonaId)).thenReturn(Optional.of(zona));

        Proveedor p1 = proveedorEnZona(zona);
        when(proveedorRepository.findById(p1.getId())).thenReturn(Optional.of(p1));

        var item1 = new RutaItemRequest(p1.getId(), 1, null);
        var item2 = new RutaItemRequest(p1.getId(), 2, null); // mismo proveedor

        assertThatThrownBy(() -> service.definirRuta(zonaId, new DefinirRutaZonaRequest(List.of(item1, item2))))
                .isInstanceOf(ValidacionNegocioException.class);
    }

    @Test
    void rechazaProveedorQueNoPerteneceActualmenteALaZona() {
        when(zonaRepository.findById(zonaId)).thenReturn(Optional.of(zonaConId(zonaId)));

        UUID otraZonaId = UUID.randomUUID();
        Proveedor proveedorDeOtraZona = new Proveedor();
        proveedorDeOtraZona.setId(UUID.randomUUID());
        proveedorDeOtraZona.setNombre("Proveedor de otra zona");
        proveedorDeOtraZona.setZonaActual(zonaConId(otraZonaId));

        when(proveedorRepository.findById(proveedorDeOtraZona.getId())).thenReturn(Optional.of(proveedorDeOtraZona));

        var item = new RutaItemRequest(proveedorDeOtraZona.getId(), 1, null);

        assertThatThrownBy(() -> service.definirRuta(zonaId, new DefinirRutaZonaRequest(List.of(item))))
                .isInstanceOf(ValidacionNegocioException.class);

        verify(repository, never()).deleteByZonaId(any());
    }

    @Test
    void definirRutaReemplazaElOrdenAnteriorCompleto() {
        Zona zona = zonaConId(zonaId);
        when(zonaRepository.findById(zonaId)).thenReturn(Optional.of(zona));

        Proveedor p1 = proveedorEnZona(zona);
        Proveedor p2 = proveedorEnZona(zona);
        when(proveedorRepository.findById(p1.getId())).thenReturn(Optional.of(p1));
        when(proveedorRepository.findById(p2.getId())).thenReturn(Optional.of(p2));

        when(repository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        var dto = new DefinirRutaZonaRequest(List.of(
                new RutaItemRequest(p1.getId(), 1, null),
                new RutaItemRequest(p2.getId(), 2, null)
        ));

        List<com.planta.acopio.dto.RutaProveedorOrdenResponse> resultado = service.definirRuta(zonaId, dto);

        // Borra el orden previo ANTES de guardar el nuevo (semantica de reemplazo total).
        var orden = inOrder(repository);
        orden.verify(repository).deleteByZonaId(zonaId);
        orden.verify(repository).flush();
        orden.verify(repository).saveAll(anyList());

        assertThat(resultado).hasSize(2);
        assertThat(resultado).extracting("proveedorId").containsExactlyInAnyOrder(p1.getId(), p2.getId());
    }

    @Test
    void rechazaSiLaZonaNoExiste() {
        when(zonaRepository.findById(zonaId)).thenReturn(Optional.empty());

        var dto = new DefinirRutaZonaRequest(List.of(new RutaItemRequest(UUID.randomUUID(), 1, null)));

        assertThatThrownBy(() -> service.definirRuta(zonaId, dto))
                .isInstanceOf(RecursoNoEncontradoException.class);
    }

    private Zona zonaConId(UUID id) {
        Zona zona = new Zona();
        zona.setId(id);
        zona.setNombre("Zona Test");
        return zona;
    }

    private Proveedor proveedorEnZona(Zona zona) {
        Proveedor p = new Proveedor();
        p.setId(UUID.randomUUID());
        p.setNombre("Proveedor " + p.getId());
        p.setZonaActual(zona);
        return p;
    }
}
