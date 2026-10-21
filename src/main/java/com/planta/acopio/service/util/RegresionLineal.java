package com.planta.acopio.service.util;

// Regresion lineal simple (minimos cuadrados) para el simulador "que pasa si"
// del panel economico (RF-INN-05) -- documento fuente §2.10.5 / §3.17: "una
// proyeccion simple por regresion lineal sobre los datos ya almacenados, no
// una promesa exacta".
public final class RegresionLineal {

    private final double pendiente;
    private final double interseccion;
    public final boolean valida;

    private RegresionLineal(double pendiente, double interseccion, boolean valida) {
        this.pendiente = pendiente;
        this.interseccion = interseccion;
        this.valida = valida;
    }

    public static RegresionLineal ajustar(double[] x, double[] y) {
        if (x == null || y == null || x.length != y.length || x.length < 2) {
            return new RegresionLineal(0, 0, false);
        }
        int n = x.length;
        double sumaX = 0, sumaY = 0, sumaXY = 0, sumaXX = 0;
        for (int i = 0; i < n; i++) {
            sumaX += x[i];
            sumaY += y[i];
            sumaXY += x[i] * y[i];
            sumaXX += x[i] * x[i];
        }
        double denominador = (n * sumaXX - sumaX * sumaX);
        if (denominador == 0) {
            return new RegresionLineal(0, sumaY / n, true); // sin varianza en X: recta horizontal
        }
        double pendiente = (n * sumaXY - sumaX * sumaY) / denominador;
        double interseccion = (sumaY - pendiente * sumaX) / n;
        return new RegresionLineal(pendiente, interseccion, true);
    }

    public double predecir(double x) {
        return valida ? (interseccion + pendiente * x) : 0.0;
    }
}
