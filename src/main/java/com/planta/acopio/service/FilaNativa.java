package com.planta.acopio.service;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.UUID;

// Paginacion pendiente: conversion defensiva de las columnas de queries
// nativas que devuelven Object[] (agrupaciones). Segun la version de
// Hibernate/driver, un uuid puede llegar como UUID o String, un timestamp como
// LocalDateTime o java.sql.Timestamp y un COUNT/SUM como Long, BigInteger,
// BigDecimal, etc. Centralizarlo evita ClassCastException en runtime.
final class FilaNativa {

    private FilaNativa() {
    }

    static UUID uuid(Object valor) {
        if (valor == null) return null;
        if (valor instanceof UUID u) return u;
        return UUID.fromString(valor.toString());
    }

    static String texto(Object valor) {
        return valor == null ? null : valor.toString();
    }

    static boolean bool(Object valor) {
        if (valor instanceof Boolean b) return b;
        if (valor instanceof Number n) return n.intValue() != 0;
        return valor != null && Boolean.parseBoolean(valor.toString());
    }

    static long entero(Object valor) {
        if (valor == null) return 0L;
        if (valor instanceof Number n) return n.longValue();
        return Long.parseLong(valor.toString());
    }

    static BigDecimal decimal(Object valor) {
        if (valor == null) return BigDecimal.ZERO;
        if (valor instanceof BigDecimal b) return b;
        if (valor instanceof Number n) return new BigDecimal(n.toString());
        return new BigDecimal(valor.toString());
    }

    static LocalDateTime fechaHora(Object valor) {
        if (valor == null) return null;
        if (valor instanceof LocalDateTime l) return l;
        if (valor instanceof Timestamp t) return t.toLocalDateTime();
        return LocalDateTime.parse(valor.toString().replace(' ', 'T'));
    }
}
