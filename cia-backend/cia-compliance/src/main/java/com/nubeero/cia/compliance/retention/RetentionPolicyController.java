package com.nubeero.cia.compliance.retention;

import com.nubeero.cia.common.api.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/compliance/retention-policy")
@RequiredArgsConstructor
public class RetentionPolicyController {

    private final RetentionPolicyService service;

    @GetMapping
    @PreAuthorize("hasRole('DATA_PROTECTION')")
    public ApiResponse<RetentionPolicyResponse> get() {
        return ApiResponse.success(RetentionPolicyResponse.from(service.getOrCreate()));
    }

    @PutMapping
    @PreAuthorize("hasRole('DATA_PROTECTION')")
    public ApiResponse<RetentionPolicyResponse> update(@RequestBody RetentionPolicyRequest request) {
        return ApiResponse.success(RetentionPolicyResponse.from(service.update(request)));
    }
}
