package com.nubeero.cia.portal.apps;

import com.nubeero.cia.common.api.ApiMeta;
import com.nubeero.cia.common.api.ApiResponse;
import com.nubeero.cia.portal.auth.PortalPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The app-context selector for the Partner Portal SPA — every Partner App the current
 * authenticated developer ({@link PortalPrincipal}, set by {@code PortalSessionFilter}) holds an
 * active grant on, across every tenant.
 */
@RestController
@RequestMapping("/portal/apps")
@Tag(name = "Partner Portal Apps", description = "The Partner Apps the current developer may manage")
@RequiredArgsConstructor
public class PortalAppsController {

    private final PortalAppsService appsService;

    @GetMapping
    @Operation(summary = "List the Partner Apps the current developer is granted on",
               description = "One entry per active public.partner_portal_grant row, enriched with "
                       + "the app's tenant label, rate tier, active status, and OAuth scopes. "
                       + "Returns an empty array (never a 404/403) when the developer has no grants.")
    public ResponseEntity<ApiResponse<List<PortalAppSummary>>> list(
            @AuthenticationPrincipal PortalPrincipal principal) {
        List<PortalAppSummary> apps = appsService.listApps(principal.partnerUserId());
        return ResponseEntity.ok(ApiResponse.success(apps,
                ApiMeta.builder().total(apps.size()).page(0).size(apps.size()).build()));
    }
}
