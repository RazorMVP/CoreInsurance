package com.nubeero.cia.partner.controller;

import com.nubeero.cia.common.api.ApiResponse;
import com.nubeero.cia.partner.controller.dto.PartnerQuoteResponse;
import com.nubeero.cia.quotation.QuoteService;
import com.nubeero.cia.quotation.dto.QuoteRequest;
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
@RequestMapping("/partner/v1/quotes")
@Tag(name = "Quotes", description = "Generate and retrieve insurance quotes")
@SecurityRequirement(name = "bearer-key")
@RequiredArgsConstructor
public class PartnerQuoteController {

    private final QuoteService quoteService;

    @PostMapping
    @Operation(summary = "Create a quote",
               description = "Generates a premium quote for one or more risks. Returns immediately with calculated premium. " +
                             "The quote must be approved internally before a policy can be bound.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Quote created",
            content = @Content(schema = @Schema(implementation = PartnerQuoteResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation error — missing or invalid fields", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized — invalid or expired token", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — insufficient scope", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429", description = "Rate limit exceeded", content = @Content)
    })
    public ResponseEntity<ApiResponse<PartnerQuoteResponse>> create(@Valid @RequestBody QuoteRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(PartnerQuoteResponse.from(quoteService.create(request))));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get quote details",
               description = "Returns the quote with premium breakdown and current approval status")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Quote found",
            content = @Content(schema = @Schema(implementation = PartnerQuoteResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized — invalid or expired token", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — insufficient scope", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Quote not found", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429", description = "Rate limit exceeded", content = @Content)
    })
    public ResponseEntity<ApiResponse<PartnerQuoteResponse>> get(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(PartnerQuoteResponse.from(quoteService.get(id))));
    }
}
