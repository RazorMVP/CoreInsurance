package com.nubeero.cia.policy;

import org.springframework.data.jpa.domain.Specification;

import java.util.UUID;

/** Static factory for filtering Policy list queries. Each method returns a JPA
 *  Specification that can be composed via Specification.where(...).and(...).
 *  Methods return null when their filter arg is null/blank so callers can
 *  compose without conditional logic. Mirrors {@code ReceiptSpecs} in
 *  cia-finance. */
public final class PolicySpecs {

    private PolicySpecs() {}

    public static Specification<Policy> notDeleted() {
        return (root, q, cb) -> cb.isNull(root.get("deletedAt"));
    }

    public static Specification<Policy> statusEquals(PolicyStatus status) {
        if (status == null) return null;
        return (root, q, cb) -> cb.equal(root.get("status"), status);
    }

    public static Specification<Policy> customerIdEquals(UUID customerId) {
        if (customerId == null) return null;
        return (root, q, cb) -> cb.equal(root.get("customerId"), customerId);
    }

    /** Case-insensitive OR-LIKE across the denormalised list columns — the same
     *  set matched by {@link PolicyRepository#search}. Blank/null q → null (no
     *  predicate), so it composes away when absent. */
    public static Specification<Policy> qLike(String qStr) {
        if (qStr == null || qStr.isBlank()) return null;
        final String pat = "%" + qStr.toLowerCase() + "%";
        return (root, q, cb) -> cb.or(
                cb.like(cb.lower(root.get("policyNumber")),        pat),
                cb.like(cb.lower(root.get("customerName")),        pat),
                cb.like(cb.lower(root.get("productName")),         pat),
                cb.like(cb.lower(root.get("classOfBusinessName")), pat),
                cb.like(cb.lower(root.get("brokerName")),          pat),
                cb.like(cb.lower(root.get("quoteNumber")),         pat));
    }
}
