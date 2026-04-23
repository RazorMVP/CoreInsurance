package com.nubeero.cia.audit.web;

import com.nubeero.cia.audit.alert.AlertDetectionService;
import com.nubeero.cia.audit.login.LoginAuditService;
import com.nubeero.cia.audit.login.LoginEventType;
import com.nubeero.cia.audit.login.dto.LoginAuditLogResponse;
import com.nubeero.cia.common.api.ApiMeta;
import com.nubeero.cia.common.api.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;

@RestController
@RequiredArgsConstructor
public class LoginAuditController {

    private final LoginAuditService loginAuditService;
    private final AlertDetectionService alertDetectionService;

    /** Called by the frontend Keycloak adapter on successful authentication. */
    @PostMapping("/api/v1/auth/session/start")
    public ResponseEntity<Void> sessionStart(Authentication auth, HttpServletRequest request) {
        if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
            loginAuditService.record(
                    jwt.getSubject(),
                    jwt.getClaimAsString("preferred_username"),
                    LoginEventType.LOGIN, true, null,
                    request.getRemoteAddr(),
                    request.getHeader("User-Agent"));
        }
        return ResponseEntity.ok().build();
    }

    /** Called by the frontend on logout. */
    @PostMapping("/api/v1/auth/session/end")
    public ResponseEntity<Void> sessionEnd(Authentication auth) {
        if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
            loginAuditService.record(
                    jwt.getSubject(),
                    jwt.getClaimAsString("preferred_username"),
                    LoginEventType.LOGOUT, true, null, null, null);
        }
        return ResponseEntity.ok().build();
    }

    /** Record a failed login attempt (called when JWT validation fails for a known user). */
    @PostMapping("/api/v1/auth/login/failed")
    public ResponseEntity<Void> loginFailed(
            @RequestParam String userId,
            @RequestParam(required = false) String userName,
            HttpServletRequest request) {
        String ip = request.getRemoteAddr();
        loginAuditService.record(userId, userName, LoginEventType.LOGIN_FAILED,
                false, "Invalid credentials", ip, request.getHeader("User-Agent"));
        alertDetectionService.checkFailedLogins(userId, userName, ip);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/api/v1/audit/login-logs")
    @PreAuthorize("hasAnyRole('AUDIT_VIEW', 'SETUP_UPDATE')")
    public ResponseEntity<ApiResponse<Page<LoginAuditLogResponse>>> list(
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @PageableDefault(size = 20) Pageable pageable) {

        Page<LoginAuditLogResponse> page;
        if (userId != null) {
            page = loginAuditService.listByUser(userId, pageable);
        } else if (from != null && to != null) {
            page = loginAuditService.listByDateRange(from, to, pageable);
        } else {
            page = loginAuditService.listAll(pageable);
        }
        ApiMeta meta = ApiMeta.builder()
                .total(page.getTotalElements()).page(page.getNumber()).size(page.getSize()).build();
        return ResponseEntity.ok(ApiResponse.success(page, meta));
    }
}
