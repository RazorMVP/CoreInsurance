package com.nubeero.cia.api.platform.dto;

import com.nubeero.cia.api.platform.PlatformAuditService.PlatformAuditEntry;
import java.util.List;

/** Consolidated tenant view: the registry summary plus its most-recent audit trail. */
public record TenantDetailResponse(TenantSummary tenant, List<PlatformAuditEntry> recentAudit) {}
