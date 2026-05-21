package com.nubeero.cia.setup.vehicle;

import com.nubeero.cia.common.api.ApiMeta;
import com.nubeero.cia.common.api.ApiResponse;
import com.nubeero.cia.setup.vehicle.dto.VehicleModelRequest;
import com.nubeero.cia.setup.vehicle.dto.VehicleModelResponse;
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
@RequestMapping("/api/v1/setup/vehicle-makes/{makeId}/models")
@Tag(name = "Setup — Vehicle Models", description = "Vehicle model master data, scoped under a make (e.g. Toyota → Camry / Corolla / RAV4). Nested resource under /vehicle-makes/{makeId}/models.")
@SecurityRequirement(name = "bearer-jwt")
@RequiredArgsConstructor
public class VehicleModelController {

    private final VehicleModelService service;

    @GetMapping
    @PreAuthorize("hasRole('SETUP_VIEW')")
    @Operation(summary = "List models under a make (paginated)")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Model page",
            content = @Content(schema = @Schema(implementation = VehicleModelResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Make not found", content = @Content)
    })
    public ResponseEntity<ApiResponse<List<VehicleModelResponse>>> list(
            @PathVariable UUID makeId,
            @PageableDefault(size = 20) Pageable pageable) {
        Page<VehicleModelResponse> page = service.listByMake(makeId, pageable);
        ApiMeta meta = ApiMeta.builder()
                .total(page.getTotalElements()).page(page.getNumber()).size(page.getSize()).build();
        return ResponseEntity.ok(ApiResponse.success(page.getContent(), meta));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('SETUP_VIEW')")
    @Operation(summary = "Get vehicle model by id (scoped to its make)")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Found",
            content = @Content(schema = @Schema(implementation = VehicleModelResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Make or model not found", content = @Content)
    })
    public ResponseEntity<ApiResponse<VehicleModelResponse>> get(
            @PathVariable UUID makeId, @PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(service.get(makeId, id)));
    }

    @PostMapping
    @PreAuthorize("hasRole('SETUP_CREATE')")
    @Operation(summary = "Create a model under a make")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Created",
            content = @Content(schema = @Schema(implementation = VehicleModelResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation error", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Make not found", content = @Content)
    })
    public ResponseEntity<ApiResponse<VehicleModelResponse>> create(
            @PathVariable UUID makeId, @Valid @RequestBody VehicleModelRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(service.create(makeId, request)));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('SETUP_UPDATE')")
    @Operation(summary = "Update vehicle model")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Updated",
            content = @Content(schema = @Schema(implementation = VehicleModelResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation error", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Make or model not found", content = @Content)
    })
    public ResponseEntity<ApiResponse<VehicleModelResponse>> update(
            @PathVariable UUID makeId, @PathVariable UUID id,
            @Valid @RequestBody VehicleModelRequest request) {
        return ResponseEntity.ok(ApiResponse.success(service.update(makeId, id, request)));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('SETUP_DELETE')")
    @Operation(summary = "Soft-delete vehicle model")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Deleted"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Make or model not found", content = @Content)
    })
    public ResponseEntity<ApiResponse<Void>> delete(
            @PathVariable UUID makeId, @PathVariable UUID id) {
        service.delete(makeId, id);
        return ResponseEntity.ok(ApiResponse.success(null));
    }
}
