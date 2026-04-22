package com.nubeero.cia.partner.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nubeero.cia.common.exception.ResourceNotFoundException;
import com.nubeero.cia.partner.app.PartnerAppRepository;
import com.nubeero.cia.partner.webhook.dto.RegisterWebhookRequest;
import com.nubeero.cia.workflow.TemporalQueues;
import com.nubeero.cia.workflow.webhook.WebhookDispatchRequest;
import com.nubeero.cia.workflow.webhook.WebhookDispatchWorkflow;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WebhookService {

    private final WebhookRegistrationRepository registrationRepository;
    private final PartnerAppRepository partnerAppRepository;
    private final WorkflowClient workflowClient;
    private final ObjectMapper objectMapper;

    // ─── Registration CRUD ────────────────────────────────────────────────

    @Transactional
    public WebhookRegistration register(UUID partnerAppId, RegisterWebhookRequest req) {
        partnerAppRepository.findById(partnerAppId)
                .filter(a -> a.isActive() && a.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("PartnerApp", partnerAppId));

        WebhookRegistration reg = WebhookRegistration.builder()
                .partnerAppId(partnerAppId)
                .targetUrl(req.targetUrl())
                .secret(req.secret())
                .eventTypes(String.join(",", req.eventTypes()))
                .active(true)
                .build();
        return registrationRepository.save(reg);
    }

    public Page<WebhookRegistration> list(UUID partnerAppId, Pageable pageable) {
        return registrationRepository.findAllByPartnerAppIdAndDeletedAtIsNull(partnerAppId, pageable);
    }

    public WebhookRegistration findOrThrow(UUID id) {
        return registrationRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new ResourceNotFoundException("WebhookRegistration", id));
    }

    @Transactional
    public void delete(UUID id) {
        WebhookRegistration reg = findOrThrow(id);
        reg.setActive(false);
        reg.softDelete();
        registrationRepository.save(reg);
    }

    // ─── Event publishing ─────────────────────────────────────────────────

    /**
     * Fans out a business event to all active webhook registrations that subscribe to it.
     * Each delivery is launched as an independent Temporal workflow for retry durability.
     */
    public void publish(String tenantId, WebhookEvent event, Object payload) {
        String eventName = event.eventName();
        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(Map.of(
                    "id", "evt_" + UUID.randomUUID().toString().replace("-", ""),
                    "event", eventName,
                    "timestamp", Instant.now().toString(),
                    "tenant_id", tenantId,
                    "data", payload,
                    "sandbox", false
            ));
        } catch (Exception e) {
            log.error("Failed to serialise webhook payload event={}", eventName, e);
            return;
        }

        List<WebhookRegistration> targets = registrationRepository
                .findAllByActiveTrue()
                .stream()
                .filter(r -> r.getDeletedAt() == null)
                .filter(r -> Arrays.asList(r.getEventTypes().split(",")).contains(eventName))
                .toList();

        Instant now = Instant.now();
        for (WebhookRegistration reg : targets) {
            try {
                WebhookDispatchWorkflow workflow = workflowClient.newWorkflowStub(
                        WebhookDispatchWorkflow.class,
                        WorkflowOptions.newBuilder()
                                .setWorkflowId("webhook-" + reg.getId() + "-" + now.toEpochMilli())
                                .setTaskQueue(TemporalQueues.WEBHOOK_QUEUE)
                                .build());
                WorkflowClient.start(workflow::dispatch,
                        WebhookDispatchRequest.builder()
                                .webhookRegistrationId(reg.getId().toString())
                                .tenantId(tenantId)
                                .eventType(eventName)
                                .payloadJson(payloadJson)
                                .timestamp(now)
                                .build());
            } catch (Exception ex) {
                log.warn("Could not start webhook dispatch for registration={} event={}: {}",
                        reg.getId(), eventName, ex.getMessage());
            }
        }
    }
}
