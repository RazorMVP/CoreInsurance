package com.nubeero.cia.setup.org;

import com.nubeero.cia.common.api.ApiMeta;
import com.nubeero.cia.common.api.ApiResponse;
import com.nubeero.cia.setup.org.dto.AgentRequest;
import com.nubeero.cia.setup.org.dto.AgentResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/setup/agents")
@Tag(name = "Setup — Agents", description = "NAICOM-licensed insurance agent master data. Agents represent the INSURER and earn commission on policies sold, distinct from Brokers (who represent the INSURED) and from Relationship Managers (internal staff).")
@SecurityRequirement(name = "bearer-jwt")
@RequiredArgsConstructor
public class AgentController {

    private final AgentService service;

    @GetMapping
    @PreAuthorize("hasRole('SETUP_VIEW')")
    @Operation(summary = "List agents (paginated)")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Agent page",
            content = @Content(schema = @Schema(implementation = AgentResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden", content = @Content)
    })
    public ResponseEntity<ApiResponse<List<AgentResponse>>> list(
            @PageableDefault(size = 20) Pageable pageable) {
        Page<AgentResponse> page = service.list(pageable);
        ApiMeta meta = ApiMeta.builder()
                .total(page.getTotalElements()).page(page.getNumber()).size(page.getSize()).build();
        return ResponseEntity.ok(ApiResponse.success(page.getContent(), meta));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('SETUP_VIEW')")
    @Operation(summary = "Get agent by id")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Found",
            content = @Content(schema = @Schema(implementation = AgentResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Not found", content = @Content)
    })
    public ResponseEntity<ApiResponse<AgentResponse>> get(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(service.get(id)));
    }

    @PostMapping
    @PreAuthorize("hasRole('SETUP_CREATE')")
    @Operation(summary = "Create an agent")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Created",
            content = @Content(schema = @Schema(implementation = AgentResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation error", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden", content = @Content)
    })
    public ResponseEntity<ApiResponse<AgentResponse>> create(
            @Valid @RequestBody AgentRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(service.create(request)));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('SETUP_UPDATE')")
    @Operation(summary = "Update agent")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Updated",
            content = @Content(schema = @Schema(implementation = AgentResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation error", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Not found", content = @Content)
    })
    public ResponseEntity<ApiResponse<AgentResponse>> update(
            @PathVariable UUID id, @Valid @RequestBody AgentRequest request) {
        return ResponseEntity.ok(ApiResponse.success(service.update(id, request)));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('SETUP_DELETE')")
    @Operation(summary = "Soft-delete agent")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Deleted"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Not found", content = @Content)
    })
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable UUID id, @RequestParam(required = false) String reason) {
        service.delete(id, reason);
        return ResponseEntity.ok(ApiResponse.success(null));
    }
}
