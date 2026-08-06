package com.nubeero.cia.endorsement;

import org.springframework.data.jpa.domain.Specification;

import java.util.UUID;

/** Static factory for filtering Endorsement list queries. Each method returns a
 *  JPA Specification that can be composed via Specification.where(...).and(...).
 *  Methods return null when their filter arg is null/blank so callers can
 *  compose without conditional logic. Mirrors {@code ReceiptSpecs} in
 *  cia-finance and {@code PolicySpecs} in cia-policy. */
public final class EndorsementSpecs {

    private EndorsementSpecs() {}

    public static Specification<Endorsement> notDeleted() {
        return (root, q, cb) -> cb.isNull(root.get("deletedAt"));
    }

    public static Specification<Endorsement> statusEquals(EndorsementStatus status) {
        if (status == null) return null;
        return (root, q, cb) -> cb.equal(root.get("status"), status);
    }

    public static Specification<Endorsement> policyIdEquals(UUID policyId) {
        if (policyId == null) return null;
        return (root, q, cb) -> cb.equal(root.get("policyId"), policyId);
    }

    public static Specification<Endorsement> customerIdEquals(UUID customerId) {
        if (customerId == null) return null;
        return (root, q, cb) -> cb.equal(root.get("customerId"), customerId);
    }

    public static Specification<Endorsement> endorsementTypeEquals(EndorsementType endorsementType) {
        if (endorsementType == null) return null;
        return (root, q, cb) -> cb.equal(root.get("endorsementType"), endorsementType);
    }

    /** Case-insensitive OR-LIKE across the denormalised list columns (endorsement
     *  number, policy number, customer name). Blank/null q → null (no predicate),
     *  so it composes away when absent. Greenfield — no prior {@code /search}. */
    public static Specification<Endorsement> qLike(String qStr) {
        if (qStr == null || qStr.isBlank()) return null;
        final String pat = "%" + qStr.toLowerCase() + "%";
        return (root, q, cb) -> cb.or(
                cb.like(cb.lower(root.get("endorsementNumber")), pat),
                cb.like(cb.lower(root.get("policyNumber")),      pat),
                cb.like(cb.lower(root.get("customerName")),      pat));
    }
}
