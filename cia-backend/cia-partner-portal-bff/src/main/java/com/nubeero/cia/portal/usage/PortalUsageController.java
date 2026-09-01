package com.nubeero.cia.portal.usage;

import com.nubeero.cia.common.api.ApiResponse;
import com.nubeero.cia.portal.auth.PortalPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The Usage Dashboard's data source — request volume + error rate + webhook delivery health. */
@RestController
@RequestMapping("/portal/apps/{appId}/usage")
@Tag(name = "Partner Portal Usage", description = "Per-app request telemetry and webhook delivery health")
@RequiredArgsConstructor
public class PortalUsageController {

    private final PortalUsageService usageService;

    @GetMapping
    @Operation(summary = "Get this app's usage dashboard data",
               description = "Today's live request counters, up to 30 days of durably-flushed "
                       + "history, webhook delivery health, and the computed error rate. Any "
                       + "active grant suffices (read-only).")
    public ResponseEntity<ApiResponse<PortalUsageResponse>> usage(
            @PathVariable UUID appId, @AuthenticationPrincipal PortalPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.success(usageService.usage(principal.partnerUserId(), appId)));
    }
}
