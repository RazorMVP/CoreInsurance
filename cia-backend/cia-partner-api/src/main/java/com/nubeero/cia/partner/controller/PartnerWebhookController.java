package com.nubeero.cia.partner.controller;

import com.nubeero.cia.common.api.ApiResponse;
import com.nubeero.cia.partner.controller.dto.PartnerWebhookResponse;
import com.nubeero.cia.partner.webhook.WebhookService;
import com.nubeero.cia.partner.webhook.dto.RegisterWebhookRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/partner/v1/webhooks")
@Tag(name = "Webhooks", description = "Webhook endpoint registration and management")
@SecurityRequirement(name = "bearer-key")
@RequiredArgsConstructor
public class PartnerWebhookController {

    private final WebhookService webhookService;

    @PostMapping
    @Operation(summary = "Register a webhook endpoint",
               description = "Subscribe to one or more event types. All deliveries are HMAC-SHA256 signed via the " +
                             "X-CIA-Signature header. The secret is never returned after this call.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Webhook registered",
            content = @Content(schema = @Schema(implementation = PartnerWebhookResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation error — invalid URL, secret too short, or unknown event type", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized — invalid or expired token", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — insufficient scope", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429", description = "Rate limit exceeded", content = @Content)
    })
    public ResponseEntity<ApiResponse<PartnerWebhookResponse>> register(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody RegisterWebhookRequest request) {
        UUID partnerAppId = resolvePartnerAppId(jwt);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(
                        PartnerWebhookResponse.from(webhookService.register(partnerAppId, request))));
    }

    @GetMapping
    @Operation(summary = "List registered webhooks",
               description = "Returns all webhook registrations for the authenticated partner app")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Webhooks listed",
            content = @Content(schema = @Schema(implementation = PartnerWebhookResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized — invalid or expired token", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — insufficient scope", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429", description = "Rate limit exceeded", content = @Content)
    })
    public ResponseEntity<ApiResponse<Page<PartnerWebhookResponse>>> list(
            @AuthenticationPrincipal Jwt jwt,
            @ParameterObject @PageableDefault(size = 20) Pageable pageable) {
        UUID partnerAppId = resolvePartnerAppId(jwt);
        Page<PartnerWebhookResponse> page = webhookService.list(partnerAppId, pageable)
                .map(PartnerWebhookResponse::from);
        return ResponseEntity.ok(ApiResponse.success(page));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Remove a webhook registration",
               description = "Soft-deletes the registration; pending deliveries in-flight may still complete")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "Webhook removed", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized — invalid or expired token", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden — insufficient scope", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Webhook registration not found", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429", description = "Rate limit exceeded", content = @Content)
    })
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        webhookService.delete(id);
        return ResponseEntity.noContent().build();
    }

    private UUID resolvePartnerAppId(Jwt jwt) {
        String appId = jwt.getClaimAsString("partner_app_id");
        if (appId != null) {
            return UUID.fromString(appId);
        }
        return UUID.nameUUIDFromBytes(jwt.getClaimAsString("azp").getBytes());
    }
}
