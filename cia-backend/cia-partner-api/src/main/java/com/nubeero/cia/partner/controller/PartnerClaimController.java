package com.nubeero.cia.partner.controller;

import com.nubeero.cia.claims.ClaimService;
import com.nubeero.cia.claims.dto.RegisterClaimRequest;
import com.nubeero.cia.common.api.ApiResponse;
import com.nubeero.cia.partner.controller.dto.PartnerClaimResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@Tag(name = "Claims", description = "Claim submission and status tracking")
@SecurityRequirement(name = "bearer-key")
@RequiredArgsConstructor
public class PartnerClaimController {

    private final ClaimService claimService;

    @PostMapping("/partner/v1/policies/{policyId}/claims")
    @Operation(summary = "Submit a claim notification",
               description = "Registers a new claim against the specified policy. " +
                             "The claim moves to REGISTERED status immediately; processing is handled internally.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Claim registered",
            content = @Content(schema = @Schema(implementation = PartnerClaimResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation error — missing or invalid fields", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized — invalid or expired token", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — insufficient scope", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Policy not found", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429", description = "Rate limit exceeded", content = @Content)
    })
    public ResponseEntity<ApiResponse<PartnerClaimResponse>> register(
            @PathVariable UUID policyId,
            @Valid @RequestBody RegisterClaimRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(PartnerClaimResponse.from(claimService.register(request))));
    }

    @GetMapping("/partner/v1/claims/{id}")
    @Operation(summary = "Get claim status and details",
               description = "Returns the current state of a claim including reserve, approved amount, and settlement timestamp")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Claim found",
            content = @Content(schema = @Schema(implementation = PartnerClaimResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized — invalid or expired token", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — insufficient scope", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Claim not found", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429", description = "Rate limit exceeded", content = @Content)
    })
    public ResponseEntity<ApiResponse<PartnerClaimResponse>> get(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(
                PartnerClaimResponse.from(claimService.findOrThrow(id))));
    }
}
