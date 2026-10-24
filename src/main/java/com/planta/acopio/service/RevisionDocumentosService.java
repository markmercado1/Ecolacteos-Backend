package com.planta.acopio.service;

import com.planta.acopio.dto.RevisionDocumentosResponse;
import com.planta.acopio.dto.RevisionDocumentosResponse.Observacion;
import com.planta.acopio.entity.Proveedor;
import com.planta.acopio.entity.TipoPersona;
import com.planta.acopio.repository.ProveedorRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

// Fase G: informe de los documentos de proveedores que no cumplen las reglas actuales (registros anteriores a ellas o
// cargados por fuera del sistema). Solo lectura: la decision usuario es CONSERVAR los registros y reportarlos, nunca
// corregirlos automaticamente. El padron es pequeno (decenas de fichas): se evalua en memoria con una sola consulta.
@Service
public class RevisionDocumentosService {

    private final ProveedorRepository proveedorRepository;

    public RevisionDocumentosService(ProveedorRepository proveedorRepository) {
        this.proveedorRepository = proveedorRepository;
    }

    @Transactional(readOnly = true)
    public RevisionDocumentosResponse informe() {
        List<Proveedor> proveedores = proveedorRepository.findAll();
        List<Observacion> observaciones = revisar(proveedores);
        long conObservaciones = observaciones.stream().map(Observacion::proveedorId).distinct().count();
        return new RevisionDocumentosResponse(proveedores.size(), conObservaciones, observaciones);
    }

    static List<Observacion> revisar(List<Proveedor> proveedores) {
        List<Observacion> observaciones = new ArrayList<>();

        // DNI comparables: solo digitos y completados a 8 con ceros (asi "4567890", "0456 7890" y "04567890" coinciden)
        Map<String, List<Proveedor>> porDni = new HashMap<>();
        for (Proveedor p : proveedores) {
            String comparable = dniComparable(p.getDni());
            if (comparable != null) porDni.computeIfAbsent(comparable, k -> new ArrayList<>()).add(p);
        }

        for (Proveedor p : proveedores) {
            String dni = p.getDni();
            String ruc = p.getRuc();
            boolean natural = p.getTipoPersona() != TipoPersona.EMPRESA;

            if (natural && dni != null && !DocumentoIdentidad.dniValido(dni)) {
                String digitos = dni.replaceAll("[^0-9]", "");
                if (digitos.length() == 11 && DocumentoIdentidad.rucValido(digitos)) {
                    observaciones.add(obs(p, "DNI_PARECE_RUC", "El campo DNI tiene 11 dígitos con forma de RUC («" + dni
                            + "»). Si es su RUC, regístralo en el campo RUC y escribe su DNI de 8 dígitos."));
                } else {
                    observaciones.add(obs(p, "DNI_FORMATO", "El DNI «" + dni + "» no tiene exactamente 8 dígitos"
                            + (digitos.length() == dni.length() ? " (tiene " + dni.length() + ")." : " (tiene caracteres que no son dígitos).")));
                }
            }

            String comparable = dniComparable(dni);
            if (comparable != null) {
                List<Proveedor> iguales = porDni.get(comparable);
                if (iguales.size() > 1) {
                    String otros = iguales.stream().filter(o -> o != p).map(o -> o.getNombre() + " («" + o.getDni() + "»)")
                            .sorted().reduce((a, b) -> a + ", " + b).orElse("");
                    observaciones.add(obs(p, "DNI_DUPLICADO_PROBABLE", "Su DNI coincide con el de " + otros
                            + " salvo espacios o ceros iniciales: podría ser la misma persona registrada dos veces."));
                }
            }

            if (ruc != null) {
                String prefijo = natural ? DocumentoIdentidad.PREFIJO_RUC_NATURAL : DocumentoIdentidad.PREFIJO_RUC_EMPRESA;
                if (!DocumentoIdentidad.rucValido(ruc)) {
                    observaciones.add(obs(p, "RUC_INVALIDO", "El RUC «" + ruc + "» no tiene 11 dígitos con un dígito verificador correcto."));
                } else if (!ruc.startsWith(prefijo)) {
                    observaciones.add(obs(p, "RUC_INVALIDO", "El RUC «" + ruc + "» no empieza con " + prefijo + ", que corresponde a "
                            + (natural ? "una persona natural." : "una empresa.")));
                } else if (natural && dni != null && !ruc.substring(2, 10).equals(dni)) {
                    observaciones.add(obs(p, "RUC_NO_COINCIDE_DNI", "El RUC " + ruc + " no contiene su DNI " + dni
                            + " (el RUC de una persona natural es 10 + DNI + verificador)."));
                }
                String dniDelRuc = DocumentoIdentidad.dniDelRuc(ruc);
                if (dniDelRuc != null) {
                    for (Proveedor otro : porDni.getOrDefault(dniDelRuc, List.of())) {
                        if (otro != p) {
                            observaciones.add(obs(p, "POSIBLE_MISMA_PERSONA", "Su RUC " + ruc + " contiene el DNI de " + otro.getNombre()
                                    + " («" + otro.getDni() + "»): podría ser la misma persona en dos fichas."));
                        }
                    }
                }
            }
        }
        observaciones.sort(Comparator.comparing((Observacion o) -> BusquedaTexto.plegar(o.nombre()))
                .thenComparing(Observacion::codigo));
        return observaciones;
    }

    private static String dniComparable(String dni) {
        if (dni == null) return null;
        String digitos = dni.replaceAll("[^0-9]", "");
        if (digitos.isEmpty() || digitos.length() > 8) return null;
        return "0".repeat(8 - digitos.length()) + digitos;
    }

    private static Observacion obs(Proveedor p, String codigo, String detalle) {
        return new Observacion(p.getId(), p.getNombre(), p.getTipoPersona() != null ? p.getTipoPersona().name() : null,
                p.getDni(), p.getRuc(), p.getActivo(), codigo, detalle);
    }
}
