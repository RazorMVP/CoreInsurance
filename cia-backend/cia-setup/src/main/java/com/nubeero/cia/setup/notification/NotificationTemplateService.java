package com.nubeero.cia.setup.notification;

import com.nubeero.cia.common.audit.AuditAction;
import com.nubeero.cia.common.audit.AuditService;
import com.nubeero.cia.common.exception.BusinessRuleException;
import com.nubeero.cia.common.exception.ResourceNotFoundException;
import com.nubeero.cia.common.notification.NotificationChannel;
import com.nubeero.cia.common.notification.NotificationTemplateType;
import com.nubeero.cia.common.notification.NotificationVariables;
import com.nubeero.cia.documents.notification.DefaultTemplateLoader;
import com.nubeero.cia.documents.notification.MustacheTemplateRenderer;
import com.nubeero.cia.setup.notification.dto.NotificationTemplateDefaultsResponse;
import com.nubeero.cia.setup.notification.dto.NotificationTemplatePreviewRequest;
import com.nubeero.cia.setup.notification.dto.NotificationTemplatePreviewResponse;
import com.nubeero.cia.setup.notification.dto.NotificationTemplateRequest;
import com.nubeero.cia.setup.notification.dto.NotificationTemplateResponse;
import com.nubeero.cia.setup.notification.dto.NotificationTemplateVariablesResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class NotificationTemplateService {

    private final TenantNotificationTemplateRepository repo;
    private final MustacheTemplateRenderer renderer;
    private final DefaultTemplateLoader defaults;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public List<NotificationTemplateResponse> listOverrides() {
        return repo.findAllByOrderByTemplateTypeAscChannelAsc().stream()
                .filter(e -> e.getDeletedAt() == null)
                .map(NotificationTemplateResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public NotificationTemplateDefaultsResponse listDefaults() {
        List<NotificationTemplateDefaultsResponse.Entry> entries = Arrays
                .stream(NotificationTemplateType.values())
                .flatMap(type -> Arrays.stream(NotificationChannel.values())
                        .map(channel -> new NotificationTemplateDefaultsResponse.Entry(
                                type, channel,
                                defaults.subjectFor(type, channel),
                                defaults.bodyFor(type, channel))))
                .toList();
        return new NotificationTemplateDefaultsResponse(entries);
    }

    @Transactional(readOnly = true)
    public NotificationTemplateVariablesResponse listAllowedVariables() {
        List<NotificationTemplateVariablesResponse.Entry> entries = Arrays
                .stream(NotificationTemplateType.values())
                .flatMap(type -> Arrays.stream(NotificationChannel.values())
                        .map(channel -> new NotificationTemplateVariablesResponse.Entry(
                                type, channel,
                                NotificationVariables.allowlistFor(type, channel))))
                .toList();
        return new NotificationTemplateVariablesResponse(entries);
    }

    @Transactional
    public NotificationTemplateResponse create(NotificationTemplateRequest req) {
        validateRequest(req);
        if (repo.existsByTemplateTypeAndChannel(req.templateType(), req.channel())) {
            throw new BusinessRuleException("TEMPLATE_TYPE_CHANNEL_CONFLICT",
                    "An override already exists for " + req.templateType() + "/" + req.channel());
        }
        TenantNotificationTemplate entity = TenantNotificationTemplate.builder()
                .templateType(req.templateType())
                .channel(req.channel())
                .subjectTemplate(req.subjectTemplate())
                .bodyTemplate(req.bodyTemplate())
                .build();
        TenantNotificationTemplate saved = repo.save(entity);
        auditService.log("TenantNotificationTemplate", saved.getId().toString(),
                AuditAction.CREATE, null, saved);
        return NotificationTemplateResponse.from(saved);
    }

    @Transactional
    public NotificationTemplateResponse update(UUID id, NotificationTemplateRequest req) {
        validateRequest(req);
        TenantNotificationTemplate entity = findOrThrow(id);
        // templateType + channel are immutable on update; only template content changes
        entity.setSubjectTemplate(req.subjectTemplate());
        entity.setBodyTemplate(req.bodyTemplate());
        TenantNotificationTemplate saved = repo.save(entity);
        auditService.log("TenantNotificationTemplate", id.toString(),
                AuditAction.UPDATE, null, saved);
        return NotificationTemplateResponse.from(saved);
    }

    @Transactional
    public void delete(UUID id, String reason) {
        TenantNotificationTemplate entity = findOrThrow(id);
        entity.softDelete();
        repo.save(entity);
        auditService.logWithReason("TenantNotificationTemplate", id.toString(),
                AuditAction.DELETE, entity, null, reason);
    }

    @Transactional(readOnly = true)
    public NotificationTemplatePreviewResponse preview(NotificationTemplatePreviewRequest req) {
        String subjectTemplate = (req.subjectTemplate() != null && !req.subjectTemplate().isBlank())
                ? req.subjectTemplate()
                : defaults.subjectFor(req.templateType(), req.channel());
        String bodyTemplate = (req.bodyTemplate() != null && !req.bodyTemplate().isBlank())
                ? req.bodyTemplate()
                : defaults.bodyFor(req.templateType(), req.channel());

        if (subjectTemplate != null) {
            validateAgainstAllowlist(subjectTemplate, req.templateType(), req.channel());
        }
        validateAgainstAllowlist(bodyTemplate, req.templateType(), req.channel());

        Set<String> allowlist = NotificationVariables.allowlistFor(req.templateType(), req.channel());
        Map<String, Object> filtered = renderer.filterByAllowlist(req.sampleValues(), allowlist);

        String renderedSubject = subjectTemplate == null ? null : renderer.render(subjectTemplate, filtered);
        String renderedBody = renderer.render(bodyTemplate, filtered);
        return new NotificationTemplatePreviewResponse(renderedSubject, renderedBody);
    }

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------

    private TenantNotificationTemplate findOrThrow(UUID id) {
        return repo.findById(id)
                .filter(e -> e.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("TenantNotificationTemplate", id));
    }

    private void validateRequest(NotificationTemplateRequest req) {
        boolean subjectBlank = req.subjectTemplate() == null || req.subjectTemplate().isBlank();
        boolean bodyBlank = req.bodyTemplate() == null || req.bodyTemplate().isBlank();
        if (subjectBlank && bodyBlank) {
            throw new BusinessRuleException("EMPTY_OVERRIDE",
                    "At least one of subjectTemplate or bodyTemplate must be provided");
        }
        if (req.channel() == NotificationChannel.SMS && !subjectBlank) {
            throw new BusinessRuleException("SMS_SUBJECT_NOT_ALLOWED",
                    "SMS templates may not specify a subject");
        }
        if (req.bodyTemplate() != null && req.bodyTemplate().length() > 1000) {
            throw new BusinessRuleException("TEMPLATE_TOO_LONG",
                    "bodyTemplate must be at most 1000 characters");
        }
        if (req.subjectTemplate() != null && !req.subjectTemplate().isBlank()) {
            validateAgainstAllowlist(req.subjectTemplate(), req.templateType(), req.channel());
        }
        if (req.bodyTemplate() != null && !req.bodyTemplate().isBlank()) {
            validateAgainstAllowlist(req.bodyTemplate(), req.templateType(), req.channel());
        }
    }

    private void validateAgainstAllowlist(String template,
                                          NotificationTemplateType type,
                                          NotificationChannel channel) {
        Set<String> allowlist = NotificationVariables.allowlistFor(type, channel);
        Set<String> referenced = renderer.extractVariableNames(template);
        for (String name : referenced) {
            if (!allowlist.contains(name)) {
                throw new BusinessRuleException("UNKNOWN_TEMPLATE_VARIABLE",
                        "Template references unknown variable: " + name);
            }
        }
    }
}
