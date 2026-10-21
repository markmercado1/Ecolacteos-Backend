package com.planta.acopio.config;

import com.planta.acopio.dto.ErrorResponse;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.core.converter.ResolvedSchema;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Contact;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import org.springdoc.core.customizers.GlobalOpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// Documentacion OpenAPI/Swagger del API REST (esta tarea). springdoc-openapi
// escanea los @RestController automaticamente -- esta clase solo aporta lo
// que no puede inferir solo: metadata general, el esquema de autenticacion
// JWT (para el boton "Authorize" de Swagger UI) y las respuestas de error
// comunes a casi todos los endpoints (401/403/500), que de otro modo habria
// que repetir a mano en las 70+ operaciones del controller.
@OpenAPIDefinition(
        info = @Info(
                title = "EcoLácteos — API de Acopio Lechero",
                version = "v1",
                description = """
                        API REST del CORE de acopio, calidad, recepción en planta, producción, pagos, \
                        ventas, comunicados, sincronización offline e innovación/analítica.

                        Consumida por el panel web de administración y por la app móvil offline-first \
                        (Flutter) de los acopiadores.

                        **Autenticación:** todos los endpoints, salvo `POST /api/auth/login`, requieren \
                        un JWT Bearer válido. Iniciar sesión, copiar el `token` de la respuesta y pegarlo \
                        en el botón **Authorize** de esta página (sin el prefijo `Bearer`, Swagger lo agrega solo).

                        Ver `API_DOCUMENTATION.md` en el repositorio para el detalle de los flujos \
                        principales y las reglas de integración que esta página no puede expresar.""",
                contact = @Contact(name = "Equipo EcoLácteos")
        ),
        security = @SecurityRequirement(name = "bearerAuth")
)
@SecurityScheme(
        name = "bearerAuth",
        type = SecuritySchemeType.HTTP,
        scheme = "bearer",
        bearerFormat = "JWT",
        description = "JWT emitido por POST /api/auth/login (o renovado por POST /api/auth/refresh). "
                + "Se envía como header: Authorization: Bearer <token>."
)
@Configuration
public class OpenApiConfig {

    // Agrega 401/403/500 a toda operacion que no los haya declarado ya de
    // forma explicita (las que documentan 404/409/422/400 propios los
    // conservan intactos -- este customizer nunca pisa una respuesta ya
    // definida en el metodo). /api/auth/login es la unica ruta sin JWT, por
    // lo que no recibe 401/403.
    @Bean
    public GlobalOpenApiCustomizer erroresComunesCustomizer() {
        return openApi -> {
            registrarSchemaError(openApi);

            if (openApi.getPaths() == null) {
                return;
            }
            openApi.getPaths().forEach((path, item) -> item.readOperations().forEach(op -> {
                if (op.getResponses() == null) {
                    return;
                }
                boolean esLogin = "/api/auth/login".equals(path);
                if (!esLogin && !op.getResponses().containsKey("401")) {
                    op.getResponses().addApiResponse("401",
                            respuestaError("No autenticado: falta el header Authorization o el token es invalido/expirado."));
                }
                if (!esLogin && !op.getResponses().containsKey("403")) {
                    op.getResponses().addApiResponse("403",
                            respuestaError("Autenticado, pero el rol del usuario no tiene permiso para esta operacion."));
                }
                if (!op.getResponses().containsKey("500")) {
                    op.getResponses().addApiResponse("500", respuestaError("Error interno inesperado."));
                }
            }));
        };
    }

    private void registrarSchemaError(OpenAPI openApi) {
        ResolvedSchema resuelto = ModelConverters.getInstance().resolveAsResolvedSchema(new AnnotatedType(ErrorResponse.class));
        if (openApi.getComponents() == null) {
            openApi.setComponents(new Components());
        }
        resuelto.referencedSchemas.forEach((nombre, schema) -> openApi.getComponents().addSchemas(nombre, schema));
    }

    private ApiResponse respuestaError(String descripcion) {
        return new ApiResponse()
                .description(descripcion)
                .content(new Content().addMediaType("application/json",
                        new MediaType().schema(new Schema<>().$ref("#/components/schemas/ErrorResponse"))));
    }
}
