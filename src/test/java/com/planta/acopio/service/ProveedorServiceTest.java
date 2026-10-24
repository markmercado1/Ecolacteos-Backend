package com.planta.acopio.service;

import com.planta.acopio.entity.HistorialZona;
import com.planta.acopio.entity.Proveedor;
import com.planta.acopio.entity.Zona;
import com.planta.acopio.exception.ValidacionNegocioException;
import com.planta.acopio.repository.HistorialZonaRepository;
import com.planta.acopio.repository.ProveedorRepository;
import com.planta.acopio.repository.UsuarioRepository;
import com.planta.acopio.repository.ZonaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

// Bloque 5 (confirmado): filtros opcionales combinables para el listado ADMIN
// (distinto de listarPublico(), que ya filtra activo=true siempre).
// Paginacion real (confirmado): buscar() ahora devuelve Page<ProveedorAdminResponse>.
@ExtendWith(MockitoExtension.class)
class ProveedorServiceTest {

    @Mock private ProveedorRepository proveedorRepository;
    @Mock private ZonaRepository zonaRepository;
    @Mock private HistorialZonaRepository historialZonaRepository;
    @Mock private UsuarioRepository usuarioRepository;

    // 2026-10-22 15:30 en Lima (= 20:30 UTC)
    private static final Clock RELOJ = Clock.fixed(Instant.parse("2026-10-22T20:30:00Z"), ZoneId.of("America/Lima"));
    private static final LocalDateTime AHORA = LocalDateTime.of(2026, 10, 22, 15, 30);

    private ProveedorService service;
    private final Pageable pageable = PageRequest.of(0, 20);

    @BeforeEach
    void setUp() {
        service = new ProveedorService(proveedorRepository, zonaRepository, historialZonaRepository, usuarioRepository, RELOJ);
    }

    @Test
    void buscarSinFiltrosPropagaNullYSinBusqueda() {
        when(proveedorRepository.buscarConFiltros(isNull(), isNull(), isNull(), eq(pageable))).thenReturn(new PageImpl<>(List.of()));

        service.buscar(null, null, null, pageable);

        verify(proveedorRepository).buscarConFiltros(null, null, null, pageable);
    }

    @Test
    void buscarPorZonaYActivoLosPasaTalCualAlRepositorio() {
        UUID zonaId = UUID.randomUUID();
        when(proveedorRepository.buscarConFiltros(eq(zonaId), eq(false), isNull(), eq(pageable))).thenReturn(new PageImpl<>(List.of()));

        service.buscar(zonaId, false, "   ", pageable);

        verify(proveedorRepository).buscarConFiltros(zonaId, false, null, pageable);
    }

    @Test
    void buscarConTextoLoEnviaEnMinusculasConComodinesEscapados() {
        when(proveedorRepository.buscarConFiltros(isNull(), isNull(), eq("%juan!_50!%%"), eq(pageable)))
                .thenReturn(new PageImpl<>(List.of()));

        service.buscar(null, null, "  Juan_50% ", pageable);

        verify(proveedorRepository).buscarConFiltros(null, null, "%juan!_50!%%", pageable);
    }

    private Zona zona(String nombre) {
        Zona z = new Zona();
        z.setId(UUID.randomUUID());
        z.setNombre(nombre);
        return z;
    }

    private Proveedor proveedorEnZona(Zona zona) {
        Proveedor p = new Proveedor();
        p.setId(UUID.randomUUID());
        p.setNombre("Proveedor Test");
        p.setZonaActual(zona);
        when(proveedorRepository.findByIdParaActualizar(p.getId())).thenReturn(Optional.of(p));
        return p;
    }

    private HistorialZona periodoAbierto(Proveedor p, Zona zona, LocalDateTime inicio) {
        HistorialZona h = new HistorialZona();
        h.setProveedor(p);
        h.setZona(zona);
        h.setDesde(inicio.toLocalDate());
        h.setInicioEn(inicio);
        when(historialZonaRepository.findByProveedorIdAndFinEnIsNull(p.getId())).thenReturn(Optional.of(h));
        return h;
    }

    // El defecto original: un periodo abierto HOY se cerraba con hasta = hoy - 1 (anterior a desde, "invertido").
    // Criterio de aceptacion de la Fase A: reasignar a la zona en la que ya esta se rechaza (no abre un periodo identico).
    @Test
    void reasignarALaMismaZonaSeRechazaSinTocarElHistorial() {
        Zona actual = zona("Chugur");
        Proveedor p = proveedorEnZona(actual);
        when(zonaRepository.findById(actual.getId())).thenReturn(Optional.of(actual));

        assertThatThrownBy(() -> service.reasignarZona(p.getId(), actual.getId(), "Sin cambio"))
                .isInstanceOf(com.planta.acopio.exception.ConflictoNegocioException.class)
                .hasMessageContaining("Chugur");

        verify(historialZonaRepository, never()).save(any());
        verify(historialZonaRepository, never()).findByProveedorIdAndFinEnIsNull(any());
        assertThat(p.getZonaActual()).isSameAs(actual);
    }

    @Test
    void reasignarElMismoDiaQueSeAbrioElPeriodoNoDejaHastaAnteriorADesde() {
        Zona vieja = zona("Chugur");
        Zona nueva = zona("Bambamarca");
        Proveedor p = proveedorEnZona(vieja);
        HistorialZona abierto = periodoAbierto(p, vieja, AHORA.minusHours(6));
        when(zonaRepository.findById(nueva.getId())).thenReturn(Optional.of(nueva));

        service.reasignarZona(p.getId(), nueva.getId(), "  Cambio de ruta  ");

        assertThat(abierto.getFinEn()).isEqualTo(AHORA);
        assertThat(abierto.getHasta()).isEqualTo(abierto.getDesde()).isEqualTo(LocalDate.of(2026, 10, 22));
        assertThat(abierto.getHasta()).isAfterOrEqualTo(abierto.getDesde());

        ArgumentCaptor<HistorialZona> nuevo = ArgumentCaptor.forClass(HistorialZona.class);
        verify(historialZonaRepository).save(nuevo.capture());
        assertThat(nuevo.getValue().getZona()).isSameAs(nueva);
        assertThat(nuevo.getValue().getZonaOrigen()).isSameAs(vieja);
        assertThat(nuevo.getValue().getInicioEn()).isEqualTo(AHORA); // el nuevo empieza justo cuando termina el anterior
        assertThat(nuevo.getValue().getFinEn()).isNull();
        assertThat(nuevo.getValue().getMotivo()).isEqualTo("Cambio de ruta");
        assertThat(p.getZonaActual()).isSameAs(nueva);
    }

    @Test
    void reasignarDeUnPeriodoDeDiasAnterioresCierraConElUltimoDiaCompleto() {
        Zona vieja = zona("Chugur");
        Zona nueva = zona("Bambamarca");
        Proveedor p = proveedorEnZona(vieja);
        HistorialZona abierto = periodoAbierto(p, vieja, LocalDateTime.of(2026, 10, 15, 8, 0));
        when(zonaRepository.findById(nueva.getId())).thenReturn(Optional.of(nueva));

        service.reasignarZona(p.getId(), nueva.getId(), null);

        assertThat(abierto.getFinEn()).isEqualTo(AHORA);
        assertThat(abierto.getHasta()).isEqualTo(LocalDate.of(2026, 10, 21)); // ultimo dia completo, como antes
    }

    @Test
    void reasignarSinPeriodoAbiertoSoloAbreElNuevo() {
        Zona nueva = zona("Bambamarca");
        Proveedor p = proveedorEnZona(null);
        when(historialZonaRepository.findByProveedorIdAndFinEnIsNull(p.getId())).thenReturn(Optional.empty());
        when(zonaRepository.findById(nueva.getId())).thenReturn(Optional.of(nueva));

        service.reasignarZona(p.getId(), nueva.getId(), "   ");

        ArgumentCaptor<HistorialZona> nuevo = ArgumentCaptor.forClass(HistorialZona.class);
        verify(historialZonaRepository).save(nuevo.capture());
        assertThat(nuevo.getValue().getZonaOrigen()).isNull();
        assertThat(nuevo.getValue().getMotivo()).isNull(); // motivo en blanco = sin motivo
    }

    @Test
    void reasignarConMotivoDemasiadoLargoSeRechazaSinTocarNada() {
        Zona nueva = zona("Bambamarca");
        UUID proveedorId = UUID.randomUUID();

        assertThatThrownBy(() -> service.reasignarZona(proveedorId, nueva.getId(), "x".repeat(201)))
                .isInstanceOf(ValidacionNegocioException.class);

        verify(historialZonaRepository, never()).save(any());
        verifyNoInteractions(proveedorRepository);
    }

    @Test
    void buscarPliegaAcentosYMayusculas() {
        when(proveedorRepository.buscarConFiltros(isNull(), isNull(), eq("%maria huaman%"), eq(pageable)))
                .thenReturn(new PageImpl<>(List.of()));

        service.buscar(null, null, "  María HUAMÁN ", pageable);

        verify(proveedorRepository).buscarConFiltros(null, null, "%maria huaman%", pageable);
    }
}
