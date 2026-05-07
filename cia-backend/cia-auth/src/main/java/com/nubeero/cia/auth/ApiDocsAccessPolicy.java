package com.nubeero.cia.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.stereotype.Component;

@Component
public class ApiDocsAccessPolicy {

    private final boolean publicDocsEnabled;

    public ApiDocsAccessPolicy(@Value("${cia.api-docs.public-enabled:false}") boolean publicDocsEnabled) {
        this.publicDocsEnabled = publicDocsEnabled;
    }

    public boolean publicDocsEnabled() {
        return publicDocsEnabled;
    }

    public RequestMatcher[] requestMatchers() {
        return new RequestMatcher[] {
                new AntPathRequestMatcher("/partner/docs/**"),
                new AntPathRequestMatcher("/partner/docs"),
                new AntPathRequestMatcher("/partner/swagger-ui/**"),
                new AntPathRequestMatcher("/partner/v3/api-docs/**"),
                new AntPathRequestMatcher("/internal/docs"),
                new AntPathRequestMatcher("/internal/v3/api-docs"),
                new AntPathRequestMatcher("/webjars/**")
        };
    }
}
