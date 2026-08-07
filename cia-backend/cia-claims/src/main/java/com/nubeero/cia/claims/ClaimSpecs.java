package com.nubeero.cia.claims;

import org.springframework.data.jpa.domain.Specification;

import java.util.UUID;

/** Static factory for filtering Claim list queries. Each method returns a JPA
 *  Specification that can be composed via Specification.where(...).and(...).
 *  Methods return null when their filter arg is null/blank so callers can
 *  compose without conditional logic. Mirrors {@code PolicySpecs} in
 *  cia-policy (S5.2 server pagination). */
public final class ClaimSpecs {

    private ClaimSpecs() {}

    public static Specification<Claim> notDeleted() {
        return (root, q, cb) -> cb.isNull(root.get("deletedAt"));
    }

    public static Specification<Claim> statusEquals(ClaimStatus status) {
        if (status == null) return null;
        return (root, q, cb) -> cb.equal(root.get("status"), status);
    }

    public static Specification<Claim> policyIdEquals(UUID policyId) {
        if (policyId == null) return null;
        return (root, q, cb) -> cb.equal(root.get("policyId"), policyId);
    }

    public static Specification<Claim> customerIdEquals(UUID customerId) {
        if (customerId == null) return null;
        return (root, q, cb) -> cb.equal(root.get("customerId"), customerId);
    }

    /** Case-insensitive OR-LIKE across the denormalised list columns — the same
     *  set matched by {@link ClaimRepository#search}. Blank/null q → null (no
     *  predicate), so it composes away when absent. */
    public static Specification<Claim> qLike(String qStr) {
        if (qStr == null || qStr.isBlank()) return null;
        final String pat = "%" + qStr.toLowerCase() + "%";
        return (root, q, cb) -> cb.or(
                cb.like(cb.lower(root.get("claimNumber")),  pat),
                cb.like(cb.lower(root.get("customerName")), pat),
                cb.like(cb.lower(root.get("policyNumber")), pat));
    }
}
