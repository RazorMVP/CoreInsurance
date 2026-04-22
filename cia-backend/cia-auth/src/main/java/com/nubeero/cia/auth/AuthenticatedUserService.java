package com.nubeero.cia.auth;

import com.nubeero.cia.common.tenant.TenantContext;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

@Service
public class AuthenticatedUserService {

    public String currentUserId() {
        String sub = currentJwt().getSubject();
        return sub != null ? sub : "unknown";
    }

    public String currentUserName() {
        Jwt jwt = currentJwt();
        String preferred = jwt.getClaimAsString("preferred_username");
        if (preferred != null) return preferred;
        String name = jwt.getClaimAsString("name");
        return name != null ? name : "unknown";
    }

    public String currentTenantId() {
        return TenantContext.getTenantId();
    }

    public boolean hasRole(String role) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return false;
        String target = "ROLE_" + role.toUpperCase();
        return auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(target::equals);
    }

    private Jwt currentJwt() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof Jwt jwt)) {
            throw new IllegalStateException("No authenticated JWT principal in current context");
        }
        return jwt;
    }
}
