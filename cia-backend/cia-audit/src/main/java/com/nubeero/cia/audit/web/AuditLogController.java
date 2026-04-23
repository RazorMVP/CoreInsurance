package com.nubeero.cia.audit.web;

import com.nubeero.cia.audit.log.AuditQueryService;
import com.nubeero.cia.audit.log.dto.AuditLogFilter;
import com.nubeero.cia.audit.log.dto.AuditLogResponse;
import com.nubeero.cia.common.api.ApiMeta;
import com.nubeero.cia.common.api.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/audit/logs")
@RequiredArgsConstructor
public class AuditLogController {

    private final AuditQueryService queryService;

    @GetMapping
    @PreAuthorize("hasAnyRole('AUDIT_VIEW', 'SETUP_UPDATE')")
    public ResponseEntity<ApiResponse<Page<AuditLogResponse>>> search(
            AuditLogFilter filter,
            @PageableDefault(size = 20) Pageable pageable) {
        Page<AuditLogResponse> page = queryService.search(filter, pageable);
        ApiMeta meta = ApiMeta.builder()
                .total(page.getTotalElements())
                .page(page.getNumber())
                .size(page.getSize())
                .build();
        return ResponseEntity.ok(ApiResponse.success(page, meta));
    }
}
