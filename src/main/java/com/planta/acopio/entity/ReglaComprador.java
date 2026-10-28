package com.planta.acopio.entity;

// Quien puede comprar a un tipo de precio (V39). Cada tipo declara una; no hay condiciones especiales por tipo en el codigo.
public enum ReglaComprador {
    /** Cliente varios, un cliente o un proveedor. */
    CUALQUIERA,
    /** Un cliente expresamente habilitado para ese tipo (cliente_tipo_precio). */
    CLIENTE_HABILITADO,
    /** Un proveedor del padron. */
    PROVEEDOR_PADRON
}
