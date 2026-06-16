package com.nubeero.cia.compliance.purge;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Native eligibility + anonymize SQL for the retention purge (runs in the active tenant schema). */
@Repository
@RequiredArgsConstructor
public class CustomerPurgeRepository {

    private final EntityManager em;

    /**
     * Purge-eligible customers (design §6.3): never purged, no ACTIVE policy, and last activity
     * older than the retention cutoff. last_activity = GREATEST(max policy_end_date, max
     * claim reported_date), falling back to customers.created_at.
     */
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public List<UUID> findEligibleCustomerIds(int retentionDays) {
        Query q = em.createNativeQuery(
            "SELECT c.id FROM customers c "
          + "WHERE c.pii_purged_at IS NULL "
          + "  AND NOT EXISTS (SELECT 1 FROM policies p "
          + "                  WHERE p.customer_id = c.id AND p.status = 'ACTIVE') "
          + "  AND COALESCE( "
          + "        GREATEST( "
          + "          (SELECT MAX(p.policy_end_date) FROM policies p WHERE p.customer_id = c.id), "
          + "          (SELECT MAX(cl.reported_date) FROM claims cl WHERE cl.customer_id = c.id) "
          + "        ), c.created_at::date "
          + "      ) < (current_date - CAST(:days AS integer)) "
          + "ORDER BY c.id");
        q.setParameter("days", retentionDays);
        List<?> raw = q.getResultList();
        return raw.stream().map(r -> (UUID) r).toList();
    }
}
