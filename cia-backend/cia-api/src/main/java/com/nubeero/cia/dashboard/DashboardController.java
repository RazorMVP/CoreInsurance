package com.nubeero.cia.dashboard;

import com.nubeero.cia.common.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/dashboard")
@RequiredArgsConstructor
@Tag(name = "Dashboard", description = "Aggregated KPIs for the back-office home screen")
public class DashboardController {

    private final DashboardService dashboardService;

    @GetMapping("/stats")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Eight KPI stat cards")
    public ResponseEntity<ApiResponse<DashboardStatsDto>> stats() {
        return ResponseEntity.ok(ApiResponse.success(dashboardService.stats()));
    }

    @GetMapping("/approval-queue")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Pending approval counts by entity type")
    public ResponseEntity<ApiResponse<ApprovalQueueDto>> approvalQueue() {
        return ResponseEntity.ok(ApiResponse.success(dashboardService.approvalQueue()));
    }

    @GetMapping("/loss-ratio")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Loss ratio trend — last 6 months")
    public ResponseEntity<ApiResponse<List<LossRatioMonthDto>>> lossRatio() {
        return ResponseEntity.ok(ApiResponse.success(dashboardService.lossRatioTrend()));
    }

    @GetMapping("/renewals-due")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Policies expiring in the next 7 days, grouped by day")
    public ResponseEntity<ApiResponse<List<RenewalDayDto>>> renewalsDue() {
        return ResponseEntity.ok(ApiResponse.success(dashboardService.renewalsDue()));
    }
}
