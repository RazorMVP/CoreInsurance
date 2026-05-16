package com.nubeero.cia.finance.backfill;

import com.nubeero.cia.common.audit.AuditAction;
import com.nubeero.cia.common.audit.AuditService;
import com.nubeero.cia.common.tenant.TenantContext;
import com.nubeero.cia.finance.backfill.dto.StartBackfillRequest;
import com.nubeero.cia.finance.backfill.dto.StartBackfillResponse;
import com.nubeero.cia.workflow.TemporalQueues;
import com.nubeero.cia.workflow.backfill.BackfillRequest;
import com.nubeero.cia.workflow.backfill.RetroactiveJournalBackfillWorkflow;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * Bridge between the admin REST endpoint and the
 * {@link RetroactiveJournalBackfillWorkflow}.
 *
 * <p>The service performs three steps on the request thread:
 * <ol>
 *   <li>Build the workflow input record from the wire DTO.</li>
 *   <li>Write an {@code audit_log} row capturing who-requested-what before
 *       kicking off the workflow — the audit row exists even if the
 *       workflow start subsequently throws.</li>
 *   <li>Start the workflow asynchronously via
 *       {@link WorkflowClient#start} and return the workflow id for status
 *       polling.</li>
 * </ol>
 *
 * <p>Tenant id is read from {@link TenantContext} (set by the request-scoped
 * tenant filter) and embedded in the workflow request — Temporal worker
 * threads have no inherited tenant binding, so they read it from the
 * payload via the {@code TenantAwareWorkerInterceptor} contract.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BackfillAdminService {

    private final WorkflowClient workflowClient;
    private final AuditService auditService;

    public StartBackfillResponse startBackfill(StartBackfillRequest dto) {
        String tenantId = TenantContext.getTenantId();
        String requestedBy = currentUser();
        Instant now = Instant.now();
        String workflowId = "backfill-%s-%d".formatted(tenantId, now.toEpochMilli());

        BackfillRequest request = new BackfillRequest(
                tenantId,
                workflowId,
                requestedBy,
                dto.fromDate(),
                dto.toDate(),
                dto.eventTypes() == null ? List.of() : dto.eventTypes(),
                dto.dryRun());

        // Audit the request before starting the workflow. The CREATE action on
        // entity_type=JournalBackfillJob is the operator-visible record of
        // "X requested a backfill of Y..Z"; the workflow then writes one
        // audit row per posted JE on its own.
        auditService.log(
                "JournalBackfillJob",
                workflowId,
                AuditAction.CREATE,
                null,
                request);

        RetroactiveJournalBackfillWorkflow workflow = workflowClient.newWorkflowStub(
                RetroactiveJournalBackfillWorkflow.class,
                WorkflowOptions.newBuilder()
                        .setWorkflowId(workflowId)
                        .setTaskQueue(TemporalQueues.BACKFILL_QUEUE)
                        .build());
        WorkflowClient.start(workflow::backfill, request);

        log.info("Started backfill workflow id={} tenant={} range={}..{} dryRun={} requestedBy={}",
                workflowId, tenantId, dto.fromDate(), dto.toDate(), dto.dryRun(), requestedBy);

        return new StartBackfillResponse(workflowId, tenantId, dto.dryRun(), now);
    }

    private static String currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null ? auth.getName() : "system";
    }
}
