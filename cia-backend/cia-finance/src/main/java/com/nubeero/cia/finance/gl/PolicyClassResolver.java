package com.nubeero.cia.finance.gl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Resolves {@code class_of_business_id} for a JE posting from the
 * originating policy or claim. Used by {@link SubledgerPostingService}
 * (Slice 1.10a) for the five class-bearing events whose payload does
 * not directly carry {@code classOfBusinessId} —
 * {@code PolicyApprovedEvent} is the exception (it has the field on
 * the event itself).
 *
 * <p>Lightweight JDBC reads against {@code policies} / {@code claims}
 * tables. NO entity-layer dependency on cia-policy / cia-claims —
 * cia-finance is a downstream event consumer, and breaking that
 * boundary would couple JE posting to module-internal entity classes.
 *
 * <h2>Null-resilience</h2>
 * <p>Returns {@code null} when the policy / claim has been soft-deleted
 * or never existed. JE posting proceeds with a null
 * {@code class_of_business_id} — the V42 column is nullable for exactly
 * this case. A warning is logged so ops can investigate the missing
 * upstream record without blocking the financial event itself.
 */
@Component
@Slf4j
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class PolicyClassResolver {

    private final JdbcTemplate jdbcTemplate;

    /**
     * Returns the {@code class_of_business_id} on the given live policy,
     * or {@code null} if the policy has been soft-deleted or doesn't
     * exist.
     */
    public UUID findClassByPolicyId(UUID policyId) {
        if (policyId == null) return null;
        try {
            return jdbcTemplate.queryForObject(
                "SELECT class_of_business_id FROM policies " +
                "WHERE id = ? AND deleted_at IS NULL",
                UUID.class, policyId);
        } catch (EmptyResultDataAccessException e) {
            log.warn("PolicyClassResolver: no live policy found for id {} — "
                + "JE will be posted with null class_of_business_id", policyId);
            return null;
        }
    }

    /**
     * Returns the {@code class_of_business_id} snapshot on the given
     * live claim, or {@code null} if the claim has been soft-deleted or
     * doesn't exist. Claims carry their own class snapshot taken at
     * registration from the policy — using the claim's column avoids
     * a JOIN through {@code policies} and yields the same value for
     * normal cases (a policy's class doesn't change post-issue).
     */
    public UUID findClassByClaimId(UUID claimId) {
        if (claimId == null) return null;
        try {
            return jdbcTemplate.queryForObject(
                "SELECT class_of_business_id FROM claims " +
                "WHERE id = ? AND deleted_at IS NULL",
                UUID.class, claimId);
        } catch (EmptyResultDataAccessException e) {
            log.warn("PolicyClassResolver: no live claim found for id {} — "
                + "JE will be posted with null class_of_business_id", claimId);
            return null;
        }
    }
}
