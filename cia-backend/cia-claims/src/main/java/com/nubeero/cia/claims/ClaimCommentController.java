package com.nubeero.cia.claims;

import com.nubeero.cia.claims.dto.AddClaimCommentRequest;
import com.nubeero.cia.claims.dto.ClaimCommentResponse;
import com.nubeero.cia.common.api.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/claims/{claimId}/comments")
@RequiredArgsConstructor
public class ClaimCommentController {

    private final ClaimCommentService service;

    @GetMapping
    @PreAuthorize("hasRole('CLAIMS_VIEW')")
    public ApiResponse<Page<ClaimCommentResponse>> list(
            @PathVariable UUID claimId,
            @PageableDefault(size = 50) Pageable pageable) {
        return ApiResponse.success(service.list(claimId, pageable).map(this::toResponse));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('CLAIMS_UPDATE')")
    public ApiResponse<ClaimCommentResponse> add(
            @PathVariable UUID claimId,
            @Valid @RequestBody AddClaimCommentRequest request) {
        return ApiResponse.success(toResponse(service.add(claimId, request)));
    }

    private ClaimCommentResponse toResponse(ClaimComment c) {
        return new ClaimCommentResponse(
                c.getId(),
                c.getClaim().getId(),
                c.getBody(),
                c.getAuthorName(),
                c.getCreatedBy(),
                c.getCreatedAt()
        );
    }
}
