package com.nubeero.cia.setup.vehicle;

import com.nubeero.cia.common.api.ApiMeta;
import com.nubeero.cia.common.api.ApiResponse;
import com.nubeero.cia.setup.vehicle.dto.VehicleModelRequest;
import com.nubeero.cia.setup.vehicle.dto.VehicleModelResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/setup/vehicle-makes/{makeId}/models")
@RequiredArgsConstructor
public class VehicleModelController {

    private final VehicleModelService service;

    @GetMapping
    @PreAuthorize("hasRole('SETUP_VIEW')")
    public ResponseEntity<ApiResponse<Page<VehicleModelResponse>>> list(
            @PathVariable UUID makeId,
            @PageableDefault(size = 20) Pageable pageable) {
        Page<VehicleModelResponse> page = service.listByMake(makeId, pageable);
        ApiMeta meta = ApiMeta.builder()
                .total(page.getTotalElements()).page(page.getNumber()).size(page.getSize()).build();
        return ResponseEntity.ok(ApiResponse.success(page, meta));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('SETUP_VIEW')")
    public ResponseEntity<ApiResponse<VehicleModelResponse>> get(
            @PathVariable UUID makeId, @PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(service.get(makeId, id)));
    }

    @PostMapping
    @PreAuthorize("hasRole('SETUP_CREATE')")
    public ResponseEntity<ApiResponse<VehicleModelResponse>> create(
            @PathVariable UUID makeId, @Valid @RequestBody VehicleModelRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(service.create(makeId, request)));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('SETUP_UPDATE')")
    public ResponseEntity<ApiResponse<VehicleModelResponse>> update(
            @PathVariable UUID makeId, @PathVariable UUID id,
            @Valid @RequestBody VehicleModelRequest request) {
        return ResponseEntity.ok(ApiResponse.success(service.update(makeId, id, request)));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('SETUP_DELETE')")
    public ResponseEntity<ApiResponse<Void>> delete(
            @PathVariable UUID makeId, @PathVariable UUID id) {
        service.delete(makeId, id);
        return ResponseEntity.ok(ApiResponse.success(null));
    }
}
