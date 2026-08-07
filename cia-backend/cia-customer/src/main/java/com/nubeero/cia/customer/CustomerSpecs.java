package com.nubeero.cia.customer;

import org.springframework.data.jpa.domain.Specification;

/** Static factory for filtering Customer list queries. Each method returns a
 *  JPA Specification that can be composed via Specification.where(...).and(...).
 *  Methods return null when their filter arg is null/blank so callers can
 *  compose without conditional logic. Mirrors {@code PolicySpecs} in
 *  cia-policy (S5.2 server pagination).
 *
 *  <p><b>NDPR:</b> {@link #qLike} searches only the plain columns. The
 *  high-risk PII fields (id_number, address) are pgcrypto {@code bytea}
 *  (@ColumnTransformer) and cannot be substring-searched, so they are
 *  deliberately excluded — matching {@link CustomerRepository#search}. */
public final class CustomerSpecs {

    private CustomerSpecs() {}

    public static Specification<Customer> notDeleted() {
        return (root, q, cb) -> cb.isNull(root.get("deletedAt"));
    }

    public static Specification<Customer> typeEquals(CustomerType type) {
        if (type == null) return null;
        return (root, q, cb) -> cb.equal(root.get("customerType"), type);
    }

    public static Specification<Customer> kycStatusEquals(KycStatus kycStatus) {
        if (kycStatus == null) return null;
        return (root, q, cb) -> cb.equal(root.get("kycStatus"), kycStatus);
    }

    public static Specification<Customer> customerStatusEquals(CustomerStatus status) {
        if (status == null) return null;
        return (root, q, cb) -> cb.equal(root.get("customerStatus"), status);
    }

    /** Case-insensitive OR-LIKE across the <em>plain</em> list columns only —
     *  never the encrypted PII (id_number, address). Blank/null q → null (no
     *  predicate), so it composes away when absent. */
    public static Specification<Customer> qLike(String qStr) {
        if (qStr == null || qStr.isBlank()) return null;
        final String pat = "%" + qStr.toLowerCase() + "%";
        return (root, q, cb) -> cb.or(
                cb.like(cb.lower(root.get("customerNumber")), pat),
                cb.like(cb.lower(root.get("firstName")),      pat),
                cb.like(cb.lower(root.get("lastName")),       pat),
                cb.like(cb.lower(root.get("email")),          pat),
                cb.like(cb.lower(root.get("phone")),          pat));
    }
}
