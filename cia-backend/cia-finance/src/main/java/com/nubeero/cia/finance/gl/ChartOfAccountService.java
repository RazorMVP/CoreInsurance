package com.nubeero.cia.finance.gl;

import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Read-only access to the chart of accounts seeded by V32.
 * <p>
 * Slice 1.3 (Module 12 — Period-End Closures). CRUD is intentionally absent:
 * SYSTEM rows are immutable until the post-Phase-7 tenant customisation epic.
 * The same pattern is followed by {@code ReportDefinitionService}.
 * <p>
 * Caching: in-memory; backed by Spring's default {@code ConcurrentMapCacheManager}.
 * Each cache key is prefixed with the current tenant ID so that future
 * tenant-specific overrides (post-Phase-7) do not bleed between schemas. The
 * COA itself is essentially immutable in steady state, so no eviction policy
 * is registered for production. Tests that mutate the underlying table inject
 * the {@code CacheManager} and clear the relevant cache regions directly.
 *
 * @see ChartOfAccount
 * @see Ifrs17Role
 * @see Ifrs9Role
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ChartOfAccountService {

    public static final String CACHE_BY_CODE = "coa-by-code";
    public static final String CACHE_BY_IFRS17 = "coa-by-ifrs17-role";
    public static final String CACHE_BY_IFRS9 = "coa-by-ifrs9-role";
    public static final String CACHE_TREE = "coa-tree";

    private final ChartOfAccountRepository repository;

    /**
     * Resolves an account by its seeded code (e.g. {@code "2110"}).
     *
     * @throws ChartOfAccountNotFoundException if no active row matches the code
     */
    @Cacheable(
        cacheNames = CACHE_BY_CODE,
        key = "T(com.nubeero.cia.common.tenant.TenantContext).getTenantId() + ':' + #code"
    )
    public ChartOfAccount findByCode(String code) {
        return repository.findByCodeAndDeletedAtIsNull(code)
            .orElseThrow(() -> new ChartOfAccountNotFoundException(code));
    }

    /**
     * Returns every account tagged with the given IFRS 17 role. Used by posting
     * rule resolution in slice 2.x (e.g. find the LRC_BEL account when posting
     * a premium-receivable release).
     */
    @Cacheable(
        cacheNames = CACHE_BY_IFRS17,
        key = "T(com.nubeero.cia.common.tenant.TenantContext).getTenantId() + ':' + #role.name()"
    )
    public List<ChartOfAccount> findByIfrs17Role(Ifrs17Role role) {
        Objects.requireNonNull(role, "role must not be null");
        return repository.findByIfrs17RoleAndDeletedAtIsNullOrderByCodeAsc(role);
    }

    /**
     * Returns every account tagged with the given IFRS 9 role. Used by Phase 3
     * (investments) when classifying a holding or posting an ECL movement.
     */
    @Cacheable(
        cacheNames = CACHE_BY_IFRS9,
        key = "T(com.nubeero.cia.common.tenant.TenantContext).getTenantId() + ':' + #role.name()"
    )
    public List<ChartOfAccount> findByIfrs9Role(Ifrs9Role role) {
        Objects.requireNonNull(role, "role must not be null");
        return repository.findByIfrs9RoleAndDeletedAtIsNullOrderByCodeAsc(role);
    }

    /**
     * Returns the full chart of accounts as a tree rooted at the five
     * top-level account-type classes (codes 1000 / 2000 / 3000 / 4000 / 5000).
     * Children are sorted by {@code code} ascending. Soft-deleted rows are
     * omitted.
     */
    @Cacheable(
        cacheNames = CACHE_TREE,
        key = "T(com.nubeero.cia.common.tenant.TenantContext).getTenantId()"
    )
    public List<ChartOfAccountNode> getTree() {
        List<ChartOfAccount> all = repository.findByDeletedAtIsNullOrderByCodeAsc();
        Map<String, List<ChartOfAccount>> childrenByParentCode = new HashMap<>();
        List<ChartOfAccount> roots = new ArrayList<>();
        for (ChartOfAccount account : all) {
            if (account.getParent() == null) {
                roots.add(account);
            } else {
                childrenByParentCode
                    .computeIfAbsent(account.getParent().getCode(), k -> new ArrayList<>())
                    .add(account);
            }
        }
        return roots.stream()
            .sorted(Comparator.comparing(ChartOfAccount::getCode))
            .map(root -> toNode(root, childrenByParentCode))
            .toList();
    }

    private ChartOfAccountNode toNode(
            ChartOfAccount account,
            Map<String, List<ChartOfAccount>> childrenByParentCode) {
        List<ChartOfAccount> children = childrenByParentCode.getOrDefault(account.getCode(), List.of());
        List<ChartOfAccountNode> mapped = children.stream()
            .sorted(Comparator.comparing(ChartOfAccount::getCode))
            .map(child -> toNode(child, childrenByParentCode))
            .toList();
        return new ChartOfAccountNode(
            account.getCode(),
            account.getName(),
            account.getAccountType(),
            account.getIfrs17Role(),
            account.getIfrs9Role(),
            account.isActive(),
            mapped
        );
    }

}
