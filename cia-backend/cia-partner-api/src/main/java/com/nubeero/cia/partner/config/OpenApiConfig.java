package com.nubeero.cia.partner.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.OAuthFlow;
import io.swagger.v3.oas.models.security.OAuthFlows;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Value("${cia.partner.oauth2.token-url:http://localhost:8080/realms/cia/protocol/openid-connect/token}")
    private String tokenUrl;

    @Bean
    public OpenAPI partnerOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("CIA Partner Open API")
                        .description("REST API for Insurtech partners to access insurance products and services")
                        .version("v1")
                        .contact(new Contact().name("CIA Platform Team").email("platform@nubeero.com"))
                        .license(new License().name("Proprietary")))
                .components(new Components()
                        .addSecuritySchemes("bearer-key", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT"))
                        .addSecuritySchemes("oauth2-client-credentials", new SecurityScheme()
                                .type(SecurityScheme.Type.OAUTH2)
                                .flows(new OAuthFlows()
                                        .clientCredentials(new OAuthFlow()
                                                .tokenUrl(tokenUrl)))));
    }

    @Bean
    public GroupedOpenApi partnerApiGroup() {
        return GroupedOpenApi.builder()
                .group("partner-api")
                .pathsToMatch("/partner/v1/**")
                .build();
    }
}
