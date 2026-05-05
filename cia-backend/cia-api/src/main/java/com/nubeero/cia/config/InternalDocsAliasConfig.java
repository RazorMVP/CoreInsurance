package com.nubeero.cia.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Friendly aliases for the internal API's Swagger UI + raw OpenAPI JSON.
 *
 * <p>The Springdoc Swagger UI is mounted at {@code /partner/docs} (see
 * {@code application.yml}) and serves both the {@code partner-api} and
 * {@code internal-api} {@code GroupedOpenApi} beans through a single
 * dropdown. Calling that path with no query string lands on the partner
 * group by default, which is confusing for staff who only care about the
 * internal API.
 *
 * <p>This config adds two redirect view controllers so the natural URLs
 * resolve to the right place:
 * <ul>
 *   <li>{@code /internal/docs} → Swagger UI, internal group pre-selected</li>
 *   <li>{@code /internal/v3/api-docs} → raw internal-api OpenAPI JSON</li>
 * </ul>
 *
 * <p>The corresponding paths are added to the public allow-list in
 * {@link com.nubeero.cia.auth.SecurityConfig} so that loading the docs
 * does not require a JWT.
 */
@Configuration
public class InternalDocsAliasConfig implements WebMvcConfigurer {

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addRedirectViewController(
                "/internal/docs",
                "/partner/docs?urls.primaryName=internal-api");
        registry.addRedirectViewController(
                "/internal/v3/api-docs",
                "/partner/v3/api-docs/internal-api");
    }
}
