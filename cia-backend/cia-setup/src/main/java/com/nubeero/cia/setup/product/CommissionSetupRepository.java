package com.nubeero.cia.setup.product;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CommissionSetupRepository extends JpaRepository<CommissionSetup, UUID> {

    List<CommissionSetup> findAllByProductIdAndDeletedAtIsNull(UUID productId);

    /**
     * Find the single active {@link CommissionSetup} row for a given product +
     * source on a business date. "Active" means the row is not soft-deleted and
     * the business date falls within {@code [effective_from, effective_to]} —
     * {@code effective_to} is treated as open-ended when null.
     *
     * <p>If multiple rows match (data-quality issue — there should be exactly
     * one effective row per source per product per date) the first by created_at
     * wins. Callers should treat the empty result as "no commission configured"
     * and skip the policy snapshot rather than fail the policy creation.
     */
    @Query("""
        SELECT cs FROM CommissionSetup cs
        WHERE cs.product.id = :productId
          AND cs.commissionSource = :source
          AND cs.deletedAt IS NULL
          AND cs.effectiveFrom <= :on
          AND (cs.effectiveTo IS NULL OR cs.effectiveTo >= :on)
        ORDER BY cs.createdAt ASC
        """)
    List<CommissionSetup> findActiveForProductInternal(
            @Param("productId") UUID productId,
            @Param("source") CommissionSourceType source,
            @Param("on") LocalDate on);

    default Optional<CommissionSetup> findActiveForProduct(UUID productId,
                                                           CommissionSourceType source,
                                                           LocalDate on) {
        List<CommissionSetup> rows = findActiveForProductInternal(productId, source, on);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }
}
