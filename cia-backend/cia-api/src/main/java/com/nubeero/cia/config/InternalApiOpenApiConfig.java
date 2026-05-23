package com.nubeero.cia.config;

import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.Components;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class InternalApiOpenApiConfig {

    @Bean
    public GroupedOpenApi internalApiGroup() {
        return GroupedOpenApi.builder()
                .group("internal-api")
                .pathsToMatch("/api/v1/**")
                .addOpenApiCustomizer(internalApiCustomizer())
                .build();
    }

    private OpenApiCustomizer internalApiCustomizer() {
        return openApi -> {
            openApi.info(openApi.getInfo() != null
                    ? openApi.getInfo()
                            .title("CIA Internal API")
                            .description("Internal REST API for the Core Insurance Application (staff and system use)")
                            .version("v1")
                    : new io.swagger.v3.oas.models.info.Info()
                            .title("CIA Internal API")
                            .description("Internal REST API for the Core Insurance Application (staff and system use)")
                            .version("v1"));

            // Customizers run AFTER Springdoc's schema-discovery pass, so the
            // Components object already contains every discovered DTO schema.
            // We must MUTATE it, not replace it with `new Components()` — the
            // latter wipes ~254 paths' worth of `$ref` targets, leaving the
            // Scalar reference page schemaless. (Bug pre-Session-120: every
            // committed snapshot of internal-api.json had `components.schemas`
            // empty.)
            if (openApi.getComponents() == null) {
                openApi.components(new Components());
            }
            openApi.getComponents().addSecuritySchemes("bearer-jwt", new SecurityScheme()
                    .type(SecurityScheme.Type.HTTP)
                    .scheme("bearer")
                    .bearerFormat("JWT")
                    .description("Keycloak JWT — obtain via Keycloak login"));

            openApi.addSecurityItem(new SecurityRequirement().addList("bearer-jwt"));
        };
    }
}
