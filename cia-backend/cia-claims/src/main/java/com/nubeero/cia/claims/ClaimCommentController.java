package com.nubeero.cia.claims;

import com.nubeero.cia.claims.dto.AddClaimCommentRequest;
import com.nubeero.cia.claims.dto.ClaimCommentResponse;
import com.nubeero.cia.common.api.ApiMeta;
import com.nubeero.cia.common.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/claims/{claimId}/comments")
@Tag(name = "Claim Comments",
     description = "Append-only comment feed on a claim. Comments are an aggregate of the parent Claim; deletion is not supported (audit-grade history).")
@SecurityRequirement(name = "bearer-jwt")
@RequiredArgsConstructor
public class ClaimCommentController {

    private final ClaimCommentService service;

    @GetMapping
    @PreAuthorize("hasRole('CLAIMS_VIEW')")
    @Operation(summary = "List comments on a claim (paginated, chronological)")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Comment page",
            content = @Content(schema = @Schema(implementation = ClaimCommentResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks CLAIMS_VIEW", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Claim not found", content = @Content)
    })
    public ApiResponse<List<ClaimCommentResponse>> list(
            @PathVariable UUID claimId,
            @PageableDefault(size = 2000) Pageable pageable) {
        var page = service.list(claimId, pageable);
        return ApiResponse.success(page.map(this::toResponse).getContent(),
                ApiMeta.builder()
                        .total(page.getTotalElements())
                        .page(page.getNumber())
                        .size(page.getSize())
                        .build());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('CLAIMS_UPDATE')")
    @Operation(summary = "Add a comment to a claim",
               description = "Append-only — author is resolved from Authentication.getName(); body captured verbatim. There is no edit/delete endpoint by design.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Comment added",
            content = @Content(schema = @Schema(implementation = ClaimCommentResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "body missing or empty", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — caller lacks CLAIMS_UPDATE", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Claim not found", content = @Content)
    })
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
