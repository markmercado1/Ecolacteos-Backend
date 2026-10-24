package com.planta.acopio.service;

import com.planta.acopio.dto.ProveedorAdminResponse;
import com.planta.acopio.dto.ProveedorRequest;
import com.planta.acopio.dto.ProveedorUpdateRequest;
import com.planta.acopio.entity.Proveedor;
import com.planta.acopio.entity.TipoPersona;
import com.planta.acopio.exception.ConflictoNegocioException;
import com.planta.acopio.exception.ValidacionNegocioException;
import com.planta.acopio.repository.HistorialZonaRepository;
import com.planta.acopio.repository.ProveedorRepository;
import com.planta.acopio.repository.UsuarioRepository;
import com.planta.acopio.repository.ZonaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Clock;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// Fase G: documentos del proveedor. Persona natural = DNI obligatorio y RUC opcional (10 + DNI + verificador) en la misma
// ficha; empresa = RUC 20... y razon social, sin DNI. Un DNI o RUC sin cambios se conserva aunque no cumpla el formato.
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DocumentosProveedorServiceTest {

    @Mock private ProveedorRepository proveedorRepository;
    @Mock private ZonaRepository zonaRepository;
    @Mock private HistorialZonaRepository historialZonaRepository;
    @Mock private UsuarioRepository usuarioRepository;

    private ProveedorService service;

    @BeforeEach
    void setUp() {
        service = new ProveedorService(proveedorRepository, zonaRepository, historialZonaRepository, usuarioRepository,
                Clock.system(ZoneId.of("America/Lima")));
        when(proveedorRepository.findByDni(any())).thenReturn(Optional.empty());
        when(proveedorRepository.findByRuc(any())).thenReturn(Optional.empty());
        when(proveedorRepository.save(any(Proveedor.class))).thenAnswer(inv -> {
            Proveedor p = inv.getArgument(0);
            if (p.getId() == null) p.setId(UUID.randomUUID());
            return p;
        });
    }

    private Proveedor existente(TipoPersona tipo, String nombre, String dni, String ruc, String razonSocial) {
        Proveedor p = new Proveedor();
        p.setId(UUID.randomUUID());
        p.setTipoPersona(tipo);
        p.setNombre(nombre);
        p.setDni(dni);
        p.setRuc(ruc);
        p.setRazonSocial(razonSocial);
        when(proveedorRepository.findById(p.getId())).thenReturn(Optional.of(p));
        return p;
    }

    // ------------------------------------------------------------------ crear

    @Test
    void unClienteAnteriorSinTipoCreaUnaPersonaNaturalSinRuc() {
        ProveedorAdminResponse r = service.crear(new ProveedorRequest("Rosa Quispe", "04567890", null, null));
        assertThat(r.tipoPersona()).isEqualTo("NATURAL");
        assertThat(r.dni()).isEqualTo("04567890");
        assertThat(r.ruc()).isNull();
        assertThat(r.razonSocial()).isNull();
    }

    @Test
    void unaPersonaNaturalPuedeTenerDniYRucEnLaMismaFicha() {
        ProveedorAdminResponse r = service.crear(new ProveedorRequest(" Rosa Quispe ", "0456 7890", null, null, "natural", "10 04567890 9", null));
        assertThat(r.nombre()).isEqualTo("Rosa Quispe");
        assertThat(r.dni()).isEqualTo("04567890");
        assertThat(r.ruc()).isEqualTo("10045678909");
    }

    @Test
    void unaPersonaNaturalNecesitaNombreYUnDniDeOchoDigitos() {
        assertThatThrownBy(() -> service.crear(new ProveedorRequest("Rosa", null, null, null)))
                .isInstanceOf(ValidacionNegocioException.class).hasMessageContaining("DNI es obligatorio");
        assertThatThrownBy(() -> service.crear(new ProveedorRequest("Rosa", "4567890", null, null)))
                .isInstanceOf(ValidacionNegocioException.class).hasMessageContaining("exactamente 8 dígitos");
        assertThatThrownBy(() -> service.crear(new ProveedorRequest("  ", "04567890", null, null)))
                .isInstanceOf(ValidacionNegocioException.class).hasMessageContaining("nombre es obligatorio");
        verify(proveedorRepository, never()).save(any());
    }

    @Test
    void elRucDeUnaPersonaNaturalEsDiezMasSuDniConVerificadorCorrecto() {
        assertThatThrownBy(() -> service.crear(new ProveedorRequest("Rosa", "11111111", null, null, null, "10045678909", null)))
                .isInstanceOf(ValidacionNegocioException.class).hasMessageContaining("no corresponde al DNI 11111111");
        assertThatThrownBy(() -> service.crear(new ProveedorRequest("Rosa", "04567890", null, null, null, "10045678900", null)))
                .isInstanceOf(ValidacionNegocioException.class).hasMessageContaining("dígito verificador");
        assertThatThrownBy(() -> service.crear(new ProveedorRequest("Rosa", "04567890", null, null, null, "20601234565", null)))
                .isInstanceOf(ValidacionNegocioException.class).hasMessageContaining("empieza con 10");
        assertThatThrownBy(() -> service.crear(new ProveedorRequest("Rosa", "04567890", null, null, null, null, "ACME")))
                .isInstanceOf(ValidacionNegocioException.class).hasMessageContaining("solo se registra para una empresa");
        verify(proveedorRepository, never()).save(any());
    }

    @Test
    void unaEmpresaSeIdentificaConRucVeinteYRazonSocialYSuNombrePorDefectoEsLaRazonSocial() {
        ProveedorAdminResponse r = service.crear(new ProveedorRequest(null, null, null, null, "EMPRESA", "20601234565", " Lácteos del Valle S.A.C. "));
        assertThat(r.tipoPersona()).isEqualTo("EMPRESA");
        assertThat(r.dni()).isNull();
        assertThat(r.ruc()).isEqualTo("20601234565");
        assertThat(r.razonSocial()).isEqualTo("Lácteos del Valle S.A.C.");
        assertThat(r.nombre()).isEqualTo("Lácteos del Valle S.A.C.");

        ProveedorAdminResponse conNombre = service.crear(new ProveedorRequest("Fundo El Valle", null, null, null, "EMPRESA", "20481234566", "Agropecuaria El Valle S.R.L."));
        assertThat(conNombre.nombre()).isEqualTo("Fundo El Valle");
    }

    @Test
    void unaEmpresaSinRucSinRazonSocialOConDniSeRechaza() {
        assertThatThrownBy(() -> service.crear(new ProveedorRequest(null, null, null, null, "EMPRESA", null, "ACME")))
                .isInstanceOf(ValidacionNegocioException.class).hasMessageContaining("RUC es obligatorio");
        assertThatThrownBy(() -> service.crear(new ProveedorRequest(null, null, null, null, "EMPRESA", "20601234565", " ")))
                .isInstanceOf(ValidacionNegocioException.class).hasMessageContaining("razón social es obligatoria");
        assertThatThrownBy(() -> service.crear(new ProveedorRequest(null, "04567890", null, null, "EMPRESA", "20601234565", "ACME")))
                .isInstanceOf(ValidacionNegocioException.class).hasMessageContaining("no registra DNI");
        assertThatThrownBy(() -> service.crear(new ProveedorRequest(null, null, null, null, "EMPRESA", "10045678909", "ACME")))
                .isInstanceOf(ValidacionNegocioException.class).hasMessageContaining("empieza con 20");
        assertThatThrownBy(() -> service.crear(new ProveedorRequest(null, null, null, null, "SOCIEDAD", "20601234565", "ACME")))
                .isInstanceOf(ValidacionNegocioException.class).hasMessageContaining("Tipo de persona no válido");
        verify(proveedorRepository, never()).save(any());
    }

    @Test
    void elDniYElRucSonUnicos() {
        Proveedor otro = existente(TipoPersona.EMPRESA, "Otra", null, "20601234565", "Otra S.A.C.");
        when(proveedorRepository.findByRuc("20601234565")).thenReturn(Optional.of(otro));
        assertThatThrownBy(() -> service.crear(new ProveedorRequest(null, null, null, null, "EMPRESA", "20601234565", "ACME")))
                .isInstanceOf(ConflictoNegocioException.class).hasMessageContaining("RUC 20601234565").hasMessageContaining("Otra");

        Proveedor rosa = existente(TipoPersona.NATURAL, "Rosa", "04567890", null, null);
        when(proveedorRepository.findByDni("04567890")).thenReturn(Optional.of(rosa));
        assertThatThrownBy(() -> service.crear(new ProveedorRequest("Rosa", "04567890", null, null)))
                .isInstanceOf(ConflictoNegocioException.class).hasMessageContaining("DNI 04567890");
    }

    // ------------------------------------------------------------------ editar

    @Test
    void editarSinTocarUnDniAnteriorFueraDeFormatoNoBloqueaElRestoDeLaFicha() {
        Proveedor p = existente(TipoPersona.NATURAL, "Rosa", "123 45", null, null);
        when(proveedorRepository.findByDni("123 45")).thenReturn(Optional.of(p));

        ProveedorAdminResponse r = service.actualizar(p.getId(), new ProveedorUpdateRequest("Rosa Quispe", "123 45", "+51987654321"));

        assertThat(r.nombre()).isEqualTo("Rosa Quispe");
        assertThat(r.dni()).isEqualTo("123 45");
    }

    @Test
    void cambiarElDniLoValidaYAgregarElRucDeLaPersonaLoAsociaASuFicha() {
        Proveedor p = existente(TipoPersona.NATURAL, "Rosa", "123 45", null, null);
        assertThatThrownBy(() -> service.actualizar(p.getId(), new ProveedorUpdateRequest("Rosa", "12345", null)))
                .isInstanceOf(ValidacionNegocioException.class).hasMessageContaining("exactamente 8 dígitos");

        ProveedorAdminResponse r = service.actualizar(p.getId(), new ProveedorUpdateRequest("Rosa", "04567890", null, null, "10045678909", null));
        assertThat(r.tipoPersona()).isEqualTo("NATURAL");
        assertThat(r.dni()).isEqualTo("04567890");
        assertThat(r.ruc()).isEqualTo("10045678909");
    }

    @Test
    void cambiarElDniDeUnaPersonaConRucVuelveAComprobarQueElRucLeCorresponde() {
        Proveedor p = existente(TipoPersona.NATURAL, "Rosa", "04567890", "10045678909", null);
        assertThatThrownBy(() -> service.actualizar(p.getId(), new ProveedorUpdateRequest("Rosa", "11111111", null, null, "10045678909", null)))
                .isInstanceOf(ValidacionNegocioException.class).hasMessageContaining("no corresponde al DNI 11111111");
    }

    @Test
    void quitarElRucDeUnaPersonaNaturalEsValido() {
        Proveedor p = existente(TipoPersona.NATURAL, "Rosa", "04567890", "10045678909", null);
        ProveedorAdminResponse r = service.actualizar(p.getId(), new ProveedorUpdateRequest("Rosa", "04567890", null, null, null, null));
        assertThat(r.ruc()).isNull();
    }

    @Test
    void sinTipoEnLaEdicionSeConservaElTipoActualYUnaEmpresaSigueSinDni() {
        Proveedor p = existente(TipoPersona.EMPRESA, "ACME", null, "20601234565", "ACME S.A.C.");
        ProveedorAdminResponse r = service.actualizar(p.getId(), new ProveedorUpdateRequest("ACME Lácteos", null, null, null, "20601234565", "ACME S.A.C."));
        assertThat(r.tipoPersona()).isEqualTo("EMPRESA");
        assertThat(r.nombre()).isEqualTo("ACME Lácteos");
        assertThat(r.dni()).isNull();
    }

    @Test
    void elRucDeOtroProveedorNoSePuedeAsignarAlEditar() {
        Proveedor p = existente(TipoPersona.EMPRESA, "ACME", null, "20601234565", "ACME S.A.C.");
        Proveedor otro = existente(TipoPersona.EMPRESA, "Otra", null, "20481234566", "Otra S.A.C.");
        when(proveedorRepository.findByRuc("20481234566")).thenReturn(Optional.of(otro));
        assertThatThrownBy(() -> service.actualizar(p.getId(), new ProveedorUpdateRequest("ACME", null, null, null, "20481234566", "ACME S.A.C.")))
                .isInstanceOf(ConflictoNegocioException.class);
        // su propio RUC no choca consigo mismo
        when(proveedorRepository.findByRuc("20601234565")).thenReturn(Optional.of(p));
        assertThat(service.actualizar(p.getId(), new ProveedorUpdateRequest("ACME", null, null, null, "20601234565", "ACME S.A.C.")).ruc())
                .isEqualTo("20601234565");
    }
}
