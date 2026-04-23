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
        return openApi -> openApi
                .info(openApi.getInfo() != null
                        ? openApi.getInfo()
                                .title("CIA Internal API")
                                .description("Internal REST API for the Core Insurance Application (staff and system use)")
                                .version("v1")
                        : new io.swagger.v3.oas.models.info.Info()
                                .title("CIA Internal API")
                                .description("Internal REST API for the Core Insurance Application (staff and system use)")
                                .version("v1"))
                .components(new Components()
                        .addSecuritySchemes("bearer-jwt", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("Keycloak JWT — obtain via Keycloak login")))
                .addSecurityItem(new SecurityRequirement().addList("bearer-jwt"));
    }
}
