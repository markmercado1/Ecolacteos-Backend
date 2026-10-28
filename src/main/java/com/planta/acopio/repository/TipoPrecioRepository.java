package com.planta.acopio.repository;

import com.planta.acopio.entity.TipoPrecio;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TipoPrecioRepository extends JpaRepository<TipoPrecio, String> {

    List<TipoPrecio> findAllByOrderByCodigoAsc();
}
