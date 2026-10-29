package com.planta.acopio.importacion;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Un tipo de dato maestro que se puede importar desde Excel (proveedores, insumos; mas adelante precios, productos...).
 *
 * El flujo comun (plantilla, lectura segura, vista previa, confirmacion transaccional, reporte) lo resuelve
 * {@link ImportacionService}; cada tipo solo declara SUS columnas, SUS reglas de validacion y COMO se crea un registro. Crear
 * se hace SIEMPRE llamando al servicio de negocio que ya existe, nunca insertando en la base de datos: asi se conservan sus
 * reglas, historiales y efectos (QR, historial de zona, kardex...).
 *
 * Solo CREA registros: un duplicado se omite y se informa; actualizar existentes no esta soportado. Los datos maestros se
 * importan; los movimientos operativos (acopios, pagos, ventas...) nunca.
 *
 * @param <C> contexto de validacion: lo que el tipo consulta UNA vez por archivo (zonas, DNI ya registrados...).
 */
public interface TipoImportacion<C> {

    /** Identificador estable en la URL y dentro de la plantilla (p. ej. "proveedores"). */
    String codigo();

    /** Nombre para mostrar. */
    String titulo();

    /** Version de la plantilla: si cambian las columnas o reglas, se sube y las plantillas viejas se rechazan. */
    int version();

    List<ColumnaImportacion> columnas();

    /** Parrafos de la hoja «Instrucciones» propios del tipo (los generales los agrega la plantilla). */
    List<String> instrucciones();

    /** Valores desplegables por clave de columna (p. ej. las zonas existentes). Puede consultar la base de datos. */
    Map<String, List<String>> listasDesplegables();

    /** Claves de {@link #listasDesplegables()} que son solo una sugerencia: se acepta cualquier valor. */
    default Set<String> listasAbiertas() {
        return Set.of();
    }

    /** Consulta una vez lo necesario para validar el archivo completo (sin escribir nada). */
    C preparar(List<FilaCruda> filas);

    /** Valida una fila: formato, obligatorios y duplicados contra lo que YA existe. Sin efectos. */
    ResultadoFila validar(FilaCruda fila, C contexto);

    /** Clave que identifica un registro dentro del archivo para detectar repetidos (p. ej. el DNI); null si no aplica. */
    String claveUnica(Map<String, String> valoresValidados);

    /**
     * Verificacion justo antes de crear (la vista previa pudo quedar vieja): si devuelve un motivo, la fila se OMITE y se
     * informa en vez de crearse. Sin efectos.
     */
    Optional<String> conflictoAlConfirmar(Map<String, String> valoresValidados);

    /** Crea el registro llamando al servicio de negocio. Corre dentro de la transaccion de la confirmacion. */
    void crear(Map<String, String> valoresValidados);

    // ------------------------------------------------------------------------------------------------------------------
    // Ganchos opcionales para importaciones que crean MOVIMIENTOS (ingresos de insumos): por defecto no cambian nada, asi que
    // proveedores, insumos y clientes funcionan exactamente igual que antes.

    /**
     * Si es false, un archivo con filas invalidas o duplicadas NO se confirma salvo que el usuario pida explicitamente importar solo las
     * validas (confirmar con soloValidas=true). Por defecto true: se importan las validas y se informan las demas.
     */
    default boolean permiteImportacionParcial() {
        return true;
    }

    /** Un archivo ya confirmado (mismo contenido, mismo tipo) no se puede cargar otra vez: evita duplicar movimientos. */
    default boolean rechazaArchivoRepetido() {
        return false;
    }

    /**
     * Clave del grupo al que pertenece una fila valida (p. ej. el documento de ingreso: proveedor + tipo + numero); null = la fila va
     * sola. Las filas de un grupo se crean JUNTAS (crearGrupo) y, si alguna es invalida, ninguna se importa.
     */
    default String claveGrupo(Map<String, String> valoresValidados) {
        return null;
    }

    /** Comprobacion extra de un grupo de varias filas validas; devuelve el motivo si el grupo no se puede importar. */
    default Optional<String> validarGrupo(List<Map<String, String>> filasDelGrupo) {
        return Optional.empty();
    }

    /**
     * Crea un grupo de filas validas (o una sola fila sin grupo) llamando al servicio de negocio. `huellaArchivo` es el SHA-256 del
     * archivo: sirve para derivar identificadores estables y que repetir la importacion nunca duplique. Por defecto crea cada fila.
     */
    default void crearGrupo(String clave, List<Map<String, String>> filas, String huellaArchivo) {
        filas.forEach(this::crear);
    }
}
