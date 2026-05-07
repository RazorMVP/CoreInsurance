package com.nubeero.cia.tenant;

import com.nubeero.cia.common.api.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/v1/tenants")
public class TenantProvisioningController {

    private final TenantProvisioningService tenantProvisioningService;

    public TenantProvisioningController(TenantProvisioningService tenantProvisioningService) {
        this.tenantProvisioningService = tenantProvisioningService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ApiResponse<TenantProvisionResponse> provision(@Valid @RequestBody TenantProvisionRequest request) {
        return ApiResponse.success(tenantProvisioningService.provision(request));
    }
}
