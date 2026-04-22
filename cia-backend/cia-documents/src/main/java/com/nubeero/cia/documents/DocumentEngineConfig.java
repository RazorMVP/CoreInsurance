package com.nubeero.cia.documents;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.StringTemplateResolver;

@Configuration
public class DocumentEngineConfig {

    /**
     * Dedicated Thymeleaf engine for rendering HTML templates stored as strings
     * (fetched from MinIO or loaded from classpath). Separate from the main
     * web TemplateEngine to avoid resolver conflicts.
     */
    @Bean("documentTemplateEngine")
    public TemplateEngine documentTemplateEngine() {
        StringTemplateResolver resolver = new StringTemplateResolver();
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCacheable(false);

        TemplateEngine engine = new TemplateEngine();
        engine.setTemplateResolver(resolver);
        return engine;
    }
}
