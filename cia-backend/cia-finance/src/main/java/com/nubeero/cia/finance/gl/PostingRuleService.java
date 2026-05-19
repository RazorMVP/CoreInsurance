package com.nubeero.cia.finance.gl;

import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only access to {@link PostingRule} rows seeded by V33.
 *
 * <p>Slice 1.5 — the same {@link ChartOfAccountService} pattern: CRUD is
 * intentionally absent because SYSTEM rules are immutable until the
 * post-Phase-7 tenant-customisation epic. Reads are cached behind
 * {@link #CACHE_BY_EVENT_TYPE} (tenant-prefixed SpEL keys) so every JE
 * post avoids a DB hit on the hot path.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PostingRuleService {

    public static final String CACHE_BY_EVENT_TYPE = "posting-rule-by-event-type";

    private final PostingRuleRepository repository;

    /**
     * Resolves the active rule for an event type or throws
     * {@link PostingRuleNotFoundException} (422). Cached because every
     * sub-ledger post on the hot path calls this for either the simple
     * 2-line cases (5 events) or for the hardcoded FAC case's
     * counterparty lookup — caching prevents per-event DB round-trips.
     */
    @Cacheable(
        cacheNames = CACHE_BY_EVENT_TYPE,
        key = "T(com.nubeero.cia.common.tenant.TenantContext).getTenantId() + ':' + #sourceEventType"
    )
    public PostingRule findByEventType(String sourceEventType) {
        return repository.findBySourceEventTypeAndActiveTrueAndDeletedAtIsNull(sourceEventType)
            .orElseThrow(() -> new PostingRuleNotFoundException(sourceEventType));
    }
}
