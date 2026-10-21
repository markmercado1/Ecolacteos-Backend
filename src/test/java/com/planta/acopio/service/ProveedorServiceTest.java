package com.planta.acopio.service;

import com.planta.acopio.repository.HistorialZonaRepository;
import com.planta.acopio.repository.ProveedorRepository;
import com.planta.acopio.repository.ZonaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// Bloque 5 (confirmado): filtros opcionales combinables para el listado ADMIN
// (distinto de listarPublico(), que ya filtra activo=true siempre).
// Paginacion real (confirmado): buscar() ahora devuelve Page<ProveedorAdminResponse>.
@ExtendWith(MockitoExtension.class)
class ProveedorServiceTest {

    @Mock private ProveedorRepository proveedorRepository;
    @Mock private ZonaRepository zonaRepository;
    @Mock private HistorialZonaRepository historialZonaRepository;

    private ProveedorService service;
    private final Pageable pageable = PageRequest.of(0, 20);

    @BeforeEach
    void setUp() {
        service = new ProveedorService(proveedorRepository, zonaRepository, historialZonaRepository);
    }

    @Test
    void buscarSinFiltrosPropagaNullYBusquedaComodin() {
        when(proveedorRepository.buscarConFiltros(isNull(), isNull(), eq("%"), eq(pageable))).thenReturn(new PageImpl<>(List.of()));

        service.buscar(null, null, null, pageable);

        verify(proveedorRepository).buscarConFiltros(null, null, "%", pageable);
    }

    @Test
    void buscarPorZonaYActivoLosPasaTalCualAlRepositorio() {
        UUID zonaId = UUID.randomUUID();
        when(proveedorRepository.buscarConFiltros(eq(zonaId), eq(false), eq("%"), eq(pageable))).thenReturn(new PageImpl<>(List.of()));

        service.buscar(zonaId, false, "   ", pageable);

        verify(proveedorRepository).buscarConFiltros(zonaId, false, "%", pageable);
    }

    @Test
    void buscarConTextoLoEnviaEnMinusculasConComodinesEscapados() {
        when(proveedorRepository.buscarConFiltros(isNull(), isNull(), eq("%juan!_50!%%"), eq(pageable)))
                .thenReturn(new PageImpl<>(List.of()));

        service.buscar(null, null, "  Juan_50% ", pageable);

        verify(proveedorRepository).buscarConFiltros(null, null, "%juan!_50!%%", pageable);
    }
}
