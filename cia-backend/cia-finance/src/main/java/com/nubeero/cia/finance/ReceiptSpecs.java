package com.nubeero.cia.finance;

import jakarta.persistence.criteria.JoinType;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.util.UUID;

/** Static factory for filtering Receipt list queries. Each method returns a JPA
 *  Specification that can be composed via Specification.where(...).and(...).
 *  Methods return null when their filter arg is null so callers can compose
 *  without conditional logic. */
public final class ReceiptSpecs {

    private ReceiptSpecs() {}

    public static Specification<Receipt> deletedAtIsNull() {
        return (root, q, cb) -> cb.isNull(root.get("deletedAt"));
    }

    public static Specification<Receipt> statusEquals(TransactionStatus status) {
        if (status == null) return null;
        return (root, q, cb) -> cb.equal(root.get("status"), status);
    }

    public static Specification<Receipt> createdBetween(Instant from, Instant to) {
        if (from == null && to == null) return null;
        return (root, q, cb) -> {
            if (from != null && to != null) {
                return cb.between(root.get("createdAt"), from, to);
            }
            if (from != null) {
                return cb.greaterThanOrEqualTo(root.get("createdAt"), from);
            }
            return cb.lessThanOrEqualTo(root.get("createdAt"), to);
        };
    }

    public static Specification<Receipt> paymentMethodEquals(PaymentMethod method) {
        if (method == null) return null;
        return (root, q, cb) -> cb.equal(root.get("paymentMethod"), method);
    }

    public static Specification<Receipt> debitNoteIdEquals(UUID debitNoteId) {
        if (debitNoteId == null) return null;
        return (root, q, cb) -> cb.equal(
                root.join("debitNote", JoinType.INNER).get("id"), debitNoteId);
    }
}
