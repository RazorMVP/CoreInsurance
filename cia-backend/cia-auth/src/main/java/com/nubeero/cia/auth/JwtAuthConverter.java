package com.nubeero.cia.auth;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class JwtAuthConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        Collection<SimpleGrantedAuthority> authorities = extractAuthorities(jwt).stream()
                .map(SimpleGrantedAuthority::new)
                .toList();
        return new JwtAuthenticationToken(jwt, authorities, jwt.getSubject());
    }

    private Set<String> extractAuthorities(Jwt jwt) {
        Set<String> authorities = new LinkedHashSet<>();

        for (String role : extractRoles(jwt)) {
            authorities.add(role);
            authorities.add(toRoleAuthority(role));
            toPermissionAuthority(role).ifPresent(authorities::add);
        }

        for (String scope : extractScopes(jwt)) {
            authorities.add(scope);
            authorities.add("SCOPE_" + scope);
        }

        return authorities;
    }

    private List<String> extractRoles(Jwt jwt) {
        Map<String, Object> realmAccess = jwt.getClaimAsMap("realm_access");
        if (realmAccess == null) return Collections.emptyList();
        Object roles = realmAccess.get("roles");
        if (!(roles instanceof List<?> list)) return Collections.emptyList();
        return list.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .map(String::trim)
                .filter(role -> !role.isBlank())
                .toList();
    }

    private List<String> extractScopes(Jwt jwt) {
        List<String> scopes = new ArrayList<>();
        scopes.addAll(extractScopeClaim(jwt, "scope"));
        scopes.addAll(extractScopeClaim(jwt, "scp"));
        return scopes;
    }

    private List<String> extractScopeClaim(Jwt jwt, String claimName) {
        Object raw = jwt.getClaims().get(claimName);
        if (raw instanceof String scopeString) {
            if (scopeString.isBlank()) return Collections.emptyList();
            return List.of(scopeString.trim().split("\\s+"));
        }
        if (raw instanceof List<?> list) {
            return list.stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .map(String::trim)
                    .filter(scope -> !scope.isBlank())
                    .toList();
        }
        return Collections.emptyList();
    }

    private String toRoleAuthority(String authority) {
        String normalized = stripRolePrefix(authority)
                .replace(':', '_')
                .replace('-', '_')
                .replace('.', '_')
                .toUpperCase(Locale.ROOT);
        return "ROLE_" + normalized;
    }

    private java.util.Optional<String> toPermissionAuthority(String authority) {
        String normalized = stripRolePrefix(authority).trim();
        if (normalized.contains(":")) {
            return java.util.Optional.of(normalized.toLowerCase(Locale.ROOT));
        }

        int separator = normalized.indexOf('_');
        if (separator < 1 || separator == normalized.length() - 1) {
            return java.util.Optional.empty();
        }

        String module = normalized.substring(0, separator).toLowerCase(Locale.ROOT);
        String action = normalized.substring(separator + 1).toLowerCase(Locale.ROOT);
        return java.util.Optional.of(module + ":" + action);
    }

    private String stripRolePrefix(String authority) {
        if (authority.regionMatches(true, 0, "ROLE_", 0, "ROLE_".length())) {
            return authority.substring("ROLE_".length());
        }
        return authority;
    }
}
