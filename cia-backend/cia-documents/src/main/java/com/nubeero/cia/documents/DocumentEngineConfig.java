package com.nubeero.cia.documents;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.StringTemplateResolver;

@Configuration
public class DocumentEngineConfig {

    /**
     * Dedicated Thymeleaf engine for rendering HTML templates stored as strings
     * (fetched from MinIO or loaded from classpath). Separate from the main
     * web TemplateEngine to avoid resolver conflicts.
     *
     * <p><b>Must be a {@link SpringTemplateEngine}, not a plain
     * {@link TemplateEngine}.</b> A plain engine evaluates {@code ${...}}
     * expressions via OGNL, but Thymeleaf 3.1 made {@code ognl} an <em>optional</em>
     * dependency and {@code spring-boot-starter-thymeleaf} pulls only
     * {@code thymeleaf-spring6} (SpringEL) — so a plain engine throws
     * {@code NoClassDefFoundError: ognl/ClassResolver} the moment it evaluates a
     * template expression. {@code SpringTemplateEngine} uses SpringEL (already on
     * the classpath, and what the rest of the app uses). Spring injects the
     * ApplicationContext via {@code ApplicationContextAware}. See cia-log 2026-07-01.
     */
    @Bean("documentTemplateEngine")
    public TemplateEngine documentTemplateEngine() {
        StringTemplateResolver resolver = new StringTemplateResolver();
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCacheable(false);

        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        return engine;
    }
}
