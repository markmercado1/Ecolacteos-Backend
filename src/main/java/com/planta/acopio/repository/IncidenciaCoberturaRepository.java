package com.planta.acopio.repository;

import com.planta.acopio.entity.IncidenciaCobertura;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface IncidenciaCoberturaRepository extends JpaRepository<IncidenciaCobertura, UUID> {
}
