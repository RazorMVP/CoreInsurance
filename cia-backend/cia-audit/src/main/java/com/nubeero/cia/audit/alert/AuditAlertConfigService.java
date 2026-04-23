package com.nubeero.cia.audit.alert;

import com.nubeero.cia.audit.alert.dto.AuditAlertConfigRequest;
import com.nubeero.cia.audit.alert.dto.AuditAlertConfigResponse;
import com.nubeero.cia.common.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuditAlertConfigService {

    private final AuditAlertConfigRepository repository;

    @Transactional(readOnly = true)
    public AuditAlertConfigResponse get() {
        return AuditAlertConfigResponse.from(loadConfig());
    }

    @Transactional
    public AuditAlertConfigResponse update(AuditAlertConfigRequest request) {
        AuditAlertConfig config = loadConfig();
        config.setBusinessHoursStart(request.getBusinessHoursStart());
        config.setBusinessHoursEnd(request.getBusinessHoursEnd());
        config.setBusinessDays(request.getBusinessDays());
        config.setLargeApprovalThreshold(request.getLargeApprovalThreshold());
        config.setMaxFailedLoginAttempts(request.getMaxFailedLoginAttempts());
        config.setBulkDeleteCount(request.getBulkDeleteCount());
        config.setBulkDeleteWindowMinutes(request.getBulkDeleteWindowMinutes());
        config.setRetentionYears(request.getRetentionYears());
        return AuditAlertConfigResponse.from(repository.save(config));
    }

    public AuditAlertConfig loadConfig() {
        return repository.findFirstByOrderByCreatedAtAsc()
                .orElseThrow(() -> new ResourceNotFoundException("AuditAlertConfig", "default"));
    }
}
