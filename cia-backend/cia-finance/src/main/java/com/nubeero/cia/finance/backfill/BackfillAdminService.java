package com.nubeero.cia.finance.backfill;

import com.nubeero.cia.common.audit.AuditAction;
import com.nubeero.cia.common.audit.AuditService;
import com.nubeero.cia.common.tenant.TenantContext;
import com.nubeero.cia.finance.backfill.dto.BackfillStatusResponse;
import com.nubeero.cia.finance.backfill.dto.StartBackfillRequest;
import com.nubeero.cia.finance.backfill.dto.StartBackfillResponse;
import com.nubeero.cia.workflow.TemporalQueues;
import com.nubeero.cia.workflow.backfill.BackfillRequest;
import com.nubeero.cia.workflow.backfill.BackfillResult;
import com.nubeero.cia.workflow.backfill.RetroactiveJournalBackfillWorkflow;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.WorkflowExecutionStatus;
import io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionRequest;
import io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionResponse;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
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
 *
 * <p>Slice 1.8b adds {@link #getStatus(String)} for the GET status endpoint.
 * Implemented against Temporal's raw gRPC API ({@code describeWorkflowExecution})
 * because (a) it never blocks even if the workflow is mid-flight, and (b) the
 * gRPC surface has been stable since Temporal 1.0, whereas the SDK's typed
 * wrappers have shifted across minor versions.
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

    /**
     * Query the live status of a previously started backfill workflow.
     *
     * <p>Never blocks: the gRPC describe call returns immediately with the
     * current execution status. {@link BackfillResult} is only materialised
     * when the workflow has reached {@code COMPLETED}; for any other status
     * the result field is null and the operator should keep polling.
     *
     * <p>Returns {@link BackfillStatusResponse#notFound(String)} when Temporal
     * has no execution with the supplied workflow id — i.e. the id was never
     * started, was started under a different namespace, or has aged out of
     * Temporal's visibility retention window (default 7 days for COMPLETED;
     * configurable per Temporal cluster).
     */
    public BackfillStatusResponse getStatus(String workflowId) {
        DescribeWorkflowExecutionResponse describe;
        try {
            describe = workflowClient.getWorkflowServiceStubs()
                    .blockingStub()
                    .describeWorkflowExecution(DescribeWorkflowExecutionRequest.newBuilder()
                            .setNamespace(workflowClient.getOptions().getNamespace())
                            .setExecution(WorkflowExecution.newBuilder()
                                    .setWorkflowId(workflowId)
                                    .build())
                            .build());
        } catch (StatusRuntimeException e) {
            if (e.getStatus().getCode() == Status.Code.NOT_FOUND) {
                return BackfillStatusResponse.notFound(workflowId);
            }
            throw e;
        }

        WorkflowExecutionStatus status = describe.getWorkflowExecutionInfo().getStatus();
        String shortStatus = shortName(status);
        BackfillResult result = null;
        if (status == WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_COMPLETED) {
            // getResult on a COMPLETED workflow returns immediately — it walks
            // the workflow history and decodes the last result payload. We
            // pass through any decode exception so the operator sees the real
            // failure cause rather than a generic 500.
            WorkflowStub stub = workflowClient.newUntypedWorkflowStub(workflowId);
            result = stub.getResult(BackfillResult.class);
        }
        return new BackfillStatusResponse(workflowId, shortStatus, result);
    }

    private static String shortName(WorkflowExecutionStatus status) {
        // WORKFLOW_EXECUTION_STATUS_RUNNING → RUNNING (more pleasant on the wire).
        String full = status.name();
        String prefix = "WORKFLOW_EXECUTION_STATUS_";
        return full.startsWith(prefix) ? full.substring(prefix.length()) : full;
    }

    private static String currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null ? auth.getName() : "system";
    }
}
