package com.planta.acopio.exception;

import com.planta.acopio.dto.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.time.Instant;

// CORE_DESIGN.md §14: manejador global centralizado. Traduce excepciones a
// respuestas HTTP consistentes. Documentacion Swagger (ErrorResponse) §14:
// el body sigue siendo exactamente {timestamp, status, error, mensaje} --
// antes Map<String,Object>, ahora un record tipado para que springdoc pueda
// generar el schema real en vez de "object" generico.
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(RecursoNoEncontradoException.class)
    public ResponseEntity<ErrorResponse> handleNoEncontrado(RecursoNoEncontradoException ex) {
        return construir(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(ConflictoNegocioException.class)
    public ResponseEntity<ErrorResponse> handleConflicto(ConflictoNegocioException ex) {
        return construir(HttpStatus.CONFLICT, ex.getMessage());
    }

    // Bloque 10 (confirmado): demasiados intentos fallidos de login -> 429.
    @ExceptionHandler(DemasiadosIntentosException.class)
    public ResponseEntity<ErrorResponse> handleDemasiadosIntentos(DemasiadosIntentosException ex) {
        return construir(HttpStatus.TOO_MANY_REQUESTS, ex.getMessage());
    }

    @ExceptionHandler(RecursoInactivoException.class)
    public ResponseEntity<ErrorResponse> handleInactivo(RecursoInactivoException ex) {
        return construir(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
    }

    @ExceptionHandler(ValidacionNegocioException.class)
    public ResponseEntity<ErrorResponse> handleValidacionNegocio(ValidacionNegocioException ex) {
        return construir(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidacionBean(MethodArgumentNotValidException ex) {
        String detalle = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("Datos de entrada invalidos");
        return construir(HttpStatus.BAD_REQUEST, detalle);
    }

    // Cuerpo JSON ilegible o con un valor que no cabe en su tipo (JSON malformado, UUID invalido, enum
    // desconocido): error del cliente -> 400, no el 500 generico.
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleCuerpoIlegible(HttpMessageNotReadableException ex) {
        return construir(HttpStatus.BAD_REQUEST, "El cuerpo de la solicitud es ilegible o contiene valores no permitidos");
    }

    // Subida de archivos: falta la parte `file` -> 400; cuerpo que no es multipart -> 415 (antes ambos caian en 500).
    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<ErrorResponse> handleParteFaltante(MissingServletRequestPartException ex) {
        return construir(HttpStatus.BAD_REQUEST, "Falta el campo '" + ex.getRequestPartName() + "' en la solicitud");
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleTipoContenido(HttpMediaTypeNotSupportedException ex) {
        return construir(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Tipo de contenido no soportado para esta operación");
    }

    // Red de seguridad para unicidad/integridad que llegan hasta aqui (p. ej. dos altas simultaneas con el
    // mismo nombre que pasaron el chequeo previo del servicio): conflicto -> 409, no 500. Los servicios que
    // ya capturan esta excepcion con un mensaje propio no llegan a este handler.
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleIntegridad(DataIntegrityViolationException ex) {
        log.warn("Violacion de integridad de datos: {}", ex.getMostSpecificCause().getMessage());
        return construir(HttpStatus.CONFLICT,
                "No se pudo guardar: el dato entra en conflicto con un registro existente (por ejemplo, un nombre repetido)");
    }

    // V18: un parametro de query/ruta con formato invalido (ej. estado=FOO, un UUID mal formado, litros=abc)
    // es un error del cliente -> 400, no el 500 generico de abajo.
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTipoParametro(MethodArgumentTypeMismatchException ex) {
        return construir(HttpStatus.BAD_REQUEST, "Valor inválido para el parámetro '" + ex.getName() + "'");
    }

    // V19: un verbo no soportado (ej. DELETE sobre movimientos de kardex, que son append-only) es 405, no el
    // 500 generico de abajo.
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMetodoNoSoportado(HttpRequestMethodNotSupportedException ex) {
        return construir(HttpStatus.METHOD_NOT_ALLOWED, "Operación no permitida sobre este recurso");
    }

    // Bloque 3 (confirmado): POST /api/archivos -- sin este handler, un
    // archivo que excede spring.servlet.multipart.max-file-size caia al
    // generico de abajo -> 500 en vez de 400.
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handleArchivoDemasiadoGrande(MaxUploadSizeExceededException ex) {
        return construir(HttpStatus.BAD_REQUEST, "El archivo excede el tamaño máximo permitido");
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccesoDenegado(AccessDeniedException ex) {
        return construir(HttpStatus.FORBIDDEN, "No tiene permisos para realizar esta accion");
    }

    // Segunda auditoria (flujos end-to-end §9/§14-E): AuthService.login()
    // envuelve cualquier fallo de autenticacion (password incorrecto, usuario
    // inactivo -- ver UsuarioDetailsService) en BadCredentialsException, que
    // extiende AuthenticationException. Sin este handler caia al generico de
    // abajo -> 500 en vez de 401, y el frontend no podia distinguir "credenciales
    // invalidas" de "el servidor fallo".
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ErrorResponse> handleAutenticacion(AuthenticationException ex) {
        return construir(HttpStatus.UNAUTHORIZED, ex.getMessage());
    }

    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleRutaInexistente(org.springframework.web.servlet.resource.NoResourceFoundException ex) {
        return construir(HttpStatus.NOT_FOUND, "Recurso no encontrado");
    }

    // Fallback: no manejada explicitamente -> 500. Se registra con detalle
    // para diagnostico (no se expone el stacktrace al cliente).
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGenerico(Exception ex) {
        log.error("Error no controlado", ex);
        return construir(HttpStatus.INTERNAL_SERVER_ERROR, "Error interno inesperado");
    }

    private ResponseEntity<ErrorResponse> construir(HttpStatus status, String mensaje) {
        ErrorResponse body = new ErrorResponse(
                Instant.now().toString(), status.value(), status.getReasonPhrase(), mensaje);
        return ResponseEntity.status(status).body(body);
    }
}
