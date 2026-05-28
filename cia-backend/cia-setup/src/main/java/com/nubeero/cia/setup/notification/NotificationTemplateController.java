package com.nubeero.cia.setup.notification;

import com.nubeero.cia.common.api.ApiResponse;
import com.nubeero.cia.setup.notification.dto.NotificationTemplateDefaultsResponse;
import com.nubeero.cia.setup.notification.dto.NotificationTemplatePreviewRequest;
import com.nubeero.cia.setup.notification.dto.NotificationTemplatePreviewResponse;
import com.nubeero.cia.setup.notification.dto.NotificationTemplateRequest;
import com.nubeero.cia.setup.notification.dto.NotificationTemplateResponse;
import com.nubeero.cia.setup.notification.dto.NotificationTemplateVariablesResponse;
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
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/setup/notification-templates")
@Tag(name = "Setup — Notification Templates", description = "Per-tenant overrides for email and SMS notification templates. Overrides take precedence over JAR-bundled defaults; deleting an override restores the default.")
@SecurityRequirement(name = "bearer-jwt")
@RequiredArgsConstructor
public class NotificationTemplateController {

    private final NotificationTemplateService service;

    @GetMapping
    @PreAuthorize("hasAuthority('notification_templates:view')")
    @Operation(summary = "List tenant notification template overrides")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "List of overrides",
            content = @Content(schema = @Schema(implementation = NotificationTemplateResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden", content = @Content)
    })
    public ResponseEntity<ApiResponse<List<NotificationTemplateResponse>>> list() {
        return ResponseEntity.ok(ApiResponse.success(service.listOverrides()));
    }

    @GetMapping("/defaults")
    @PreAuthorize("hasAuthority('notification_templates:view')")
    @Operation(summary = "List all JAR-bundled default templates (read-only reference)")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "All defaults",
            content = @Content(schema = @Schema(implementation = NotificationTemplateDefaultsResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden", content = @Content)
    })
    public ResponseEntity<ApiResponse<NotificationTemplateDefaultsResponse>> defaults() {
        return ResponseEntity.ok(ApiResponse.success(service.listDefaults()));
    }

    @GetMapping("/variables")
    @PreAuthorize("hasAuthority('notification_templates:view')")
    @Operation(summary = "List allowed Mustache variables per template type and channel")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Variable allowlist",
            content = @Content(schema = @Schema(implementation = NotificationTemplateVariablesResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden", content = @Content)
    })
    public ResponseEntity<ApiResponse<NotificationTemplateVariablesResponse>> variables() {
        return ResponseEntity.ok(ApiResponse.success(service.listAllowedVariables()));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('notification_templates:update')")
    @Operation(summary = "Create a tenant notification template override")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Created",
            content = @Content(schema = @Schema(implementation = NotificationTemplateResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation error", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden", content = @Content)
    })
    public ResponseEntity<ApiResponse<NotificationTemplateResponse>> create(
            @Valid @RequestBody NotificationTemplateRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(service.create(req)));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('notification_templates:update')")
    @Operation(summary = "Update a tenant notification template override")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Updated",
            content = @Content(schema = @Schema(implementation = NotificationTemplateResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation error", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Not found", content = @Content)
    })
    public ResponseEntity<ApiResponse<NotificationTemplateResponse>> update(
            @PathVariable UUID id, @Valid @RequestBody NotificationTemplateRequest req) {
        return ResponseEntity.ok(ApiResponse.success(service.update(id, req)));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('notification_templates:update')")
    @Operation(summary = "Delete (reset) a tenant notification template override — restores the JAR default")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Deleted / reset to default"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Not found", content = @Content)
    })
    public ResponseEntity<ApiResponse<Void>> delete(
            @PathVariable UUID id,
            @RequestParam(required = false) String reason) {
        service.delete(id, reason);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @PostMapping("/preview")
    @PreAuthorize("hasAuthority('notification_templates:view')")
    @Operation(summary = "Preview a notification template rendered with sample variable values")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Rendered preview",
            content = @Content(schema = @Schema(implementation = NotificationTemplatePreviewResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation error", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden", content = @Content)
    })
    public ResponseEntity<ApiResponse<NotificationTemplatePreviewResponse>> preview(
            @Valid @RequestBody NotificationTemplatePreviewRequest req) {
        return ResponseEntity.ok(ApiResponse.success(service.preview(req)));
    }
}
