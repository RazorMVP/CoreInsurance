package com.nubeero.cia.setup.product;

import com.nubeero.cia.common.api.ApiResponse;
import com.nubeero.cia.setup.product.dto.ClaimNotificationTimelineRequest;
import com.nubeero.cia.setup.product.dto.ClaimNotificationTimelineResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/setup/products/{productId}/claim-notification-timeline")
@RequiredArgsConstructor
public class ClaimNotificationTimelineController {

    private final ClaimNotificationTimelineService service;

    @GetMapping
    @PreAuthorize("hasRole('SETUP_VIEW')")
    public ResponseEntity<ApiResponse<ClaimNotificationTimelineResponse>> get(
            @PathVariable UUID productId) {
        return ResponseEntity.ok(ApiResponse.success(service.get(productId)));
    }

    @PutMapping
    @PreAuthorize("hasRole('SETUP_UPDATE')")
    public ResponseEntity<ApiResponse<ClaimNotificationTimelineResponse>> upsert(
            @PathVariable UUID productId,
            @Valid @RequestBody ClaimNotificationTimelineRequest request) {
        return ResponseEntity.ok(ApiResponse.success(service.upsert(productId, request)));
    }
}
