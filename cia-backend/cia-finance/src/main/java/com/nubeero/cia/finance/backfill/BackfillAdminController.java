package com.nubeero.cia.finance.backfill;

import com.nubeero.cia.common.api.ApiResponse;
import com.nubeero.cia.finance.backfill.dto.BackfillStatusResponse;
import com.nubeero.cia.finance.backfill.dto.StartBackfillRequest;
import com.nubeero.cia.finance.backfill.dto.StartBackfillResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin-only endpoints that drive the retroactive JE backfill workflow
 * (Slice 1.8 — Module 12 Period-End Closures).
 *
 * <p>Both endpoints are gated by the {@code PLATFORM_ADMIN} role rather than
 * any of the finance roles: backfill is a one-time mechanism for moving from
 * "no GL history" to "all GL history reconstructed". It is intentionally out
 * of reach for normal finance day-to-day work.
 *
 * <ul>
 *   <li>{@code POST /backfill-journal-entries} — start a workflow (Slice 1.8a)</li>
 *   <li>{@code GET  /backfill-journal-entries/{workflowId}} — poll status (Slice 1.8b)</li>
 * </ul>
 *
 * @since Module 12, Slice 1.8a
 */
@RestController
@RequestMapping("/api/v1/admin/finance")
@RequiredArgsConstructor
public class BackfillAdminController {

    private final BackfillAdminService service;

    @PostMapping("/backfill-journal-entries")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ApiResponse<StartBackfillResponse> startBackfill(@Valid @RequestBody StartBackfillRequest request) {
        return ApiResponse.success(service.startBackfill(request));
    }

    @GetMapping("/backfill-journal-entries/{workflowId}")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ApiResponse<BackfillStatusResponse> getStatus(@PathVariable String workflowId) {
        return ApiResponse.success(service.getStatus(workflowId));
    }
}
