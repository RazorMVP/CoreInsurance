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

    /** Decrypted blob paths to delete from storage before the rows are erased. */
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public List<String> blobPathsFor(UUID customerId) {
        Query q = em.createNativeQuery(
            "SELECT pgp_sym_decrypt(id_document_url, current_setting('app.pii_key')) "
          + "  FROM customers WHERE id = CAST(:id AS uuid) AND id_document_url IS NOT NULL "
          + "UNION ALL "
          + "SELECT pgp_sym_decrypt(id_document_url, current_setting('app.pii_key')) "
          + "  FROM customer_directors WHERE customer_id = CAST(:id AS uuid) AND id_document_url IS NOT NULL "
          + "UNION ALL "
          + "SELECT document_path FROM customer_documents "
          + "  WHERE customer_id = CAST(:id AS uuid) AND deleted_at IS NULL");
        q.setParameter("id", customerId.toString());
        List<?> raw = q.getResultList();
        return raw.stream().filter(java.util.Objects::nonNull).map(Object::toString).toList();
    }

    /** Anonymize the master PII in place (design §6.4). Returns rows affected (0 if already purged). */
    @Transactional
    public int anonymizeCustomer(UUID customerId) {
        Query q = em.createNativeQuery(
            "UPDATE customers SET "
          + "  id_number = NULL, id_document_url = NULL, address = NULL, "
          + "  date_of_birth = NULL, email = NULL, phone = NULL, alternate_phone = NULL, "
          + "  other_names = NULL, id_type = NULL, id_expiry_date = NULL, "
          + "  gender = NULL, marital_status = NULL, city = NULL, state = NULL, contact_person = NULL, "
          + "  blacklist_reason = NULL, kyc_provider_ref = NULL, kyc_failure_reason = NULL, "
          + "  first_name = CASE WHEN customer_type = 'INDIVIDUAL' THEN '[ERASED]' ELSE first_name END, "
          + "  last_name  = CASE WHEN customer_type = 'INDIVIDUAL' THEN '[ERASED]' ELSE last_name  END, "
          + "  pii_purged_at = now(), deleted_at = COALESCE(deleted_at, now()), updated_at = now() "
          + "WHERE id = CAST(:id AS uuid) AND pii_purged_at IS NULL");
        q.setParameter("id", customerId.toString());
        return q.executeUpdate();
    }

    @Transactional
    public int deleteDirectors(UUID customerId) {
        Query q = em.createNativeQuery("DELETE FROM customer_directors WHERE customer_id = CAST(:id AS uuid)");
        q.setParameter("id", customerId.toString());
        return q.executeUpdate();
    }

    @Transactional
    public int deleteDocuments(UUID customerId) {
        Query q = em.createNativeQuery("DELETE FROM customer_documents WHERE customer_id = CAST(:id AS uuid)");
        q.setParameter("id", customerId.toString());
        return q.executeUpdate();
    }

    /**
     * Claim the window: stamp last_purge_run_at on the singleton BEFORE the purge loop. MUST live on
     * the repository (cross-bean) so the @Transactional proxy applies — a self-invoked @Transactional
     * from the Temporal-invoked activity (Task 6) is a no-op.
     */
    @Transactional
    public void stampLastPurgeRun(java.time.Instant now) {
        Query q = em.createNativeQuery(
            "UPDATE data_retention_policy SET last_purge_run_at = CAST(:now AS timestamptz) "
          + "WHERE deleted_at IS NULL");
        q.setParameter("now", now.toString());
        q.executeUpdate();
    }
}
