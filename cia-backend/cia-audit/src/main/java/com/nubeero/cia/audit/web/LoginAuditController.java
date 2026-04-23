package com.nubeero.cia.audit.web;

import com.nubeero.cia.audit.alert.AlertDetectionService;
import com.nubeero.cia.audit.login.LoginAuditService;
import com.nubeero.cia.audit.login.LoginEventType;
import com.nubeero.cia.audit.login.dto.LoginAuditLogResponse;
import com.nubeero.cia.common.api.ApiMeta;
import com.nubeero.cia.common.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "Login & Session Logs", description = "Login, logout, and failed authentication event recording and retrieval")
public class LoginAuditController {

    private final LoginAuditService loginAuditService;
    private final AlertDetectionService alertDetectionService;

    @PostMapping("/api/v1/auth/session/start")
    @Operation(summary = "Record login event", description = "Called by the frontend after successful Keycloak authentication. Requires a valid JWT.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Login event recorded"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized")
    })
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

    @PostMapping("/api/v1/auth/session/end")
    @Operation(summary = "Record logout event", description = "Called by the frontend on logout. Requires a valid JWT.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Logout event recorded"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized")
    })
    public ResponseEntity<Void> sessionEnd(Authentication auth) {
        if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
            loginAuditService.record(
                    jwt.getSubject(),
                    jwt.getClaimAsString("preferred_username"),
                    LoginEventType.LOGOUT, true, null, null, null);
        }
        return ResponseEntity.ok().build();
    }

    @PostMapping("/api/v1/auth/login/failed")
    @Operation(
        summary = "Record failed login attempt",
        description = "Public endpoint — no JWT required. Called when JWT validation fails for a known user. Triggers failed-login alert detection."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Failure event recorded")
    })
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
    @Operation(summary = "List login & session logs", description = "Filterable by userId or date range. Requires AUDIT_VIEW or SETUP_UPDATE role.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Paginated login log entries"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Insufficient role")
    })
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
