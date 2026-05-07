package com.nubeero.cia.workflow.approval;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class ApprovalActivityImpl implements ApprovalActivity {

    @Override
    public void notifyApprovers(ApprovalRequest request) {
        log.info("Approval workflow started entityType={} entityId={} tenantId={}",
                request.getEntityType(), request.getEntityId(), request.getTenantId());
    }

    @Override
    public void finaliseApproval(String entityType, String entityId, String tenantId,
            boolean approved, String approverId, String comments) {
        log.info("Approval workflow completed entityType={} entityId={} tenantId={} approved={}",
                entityType, entityId, tenantId, approved);
    }
}
