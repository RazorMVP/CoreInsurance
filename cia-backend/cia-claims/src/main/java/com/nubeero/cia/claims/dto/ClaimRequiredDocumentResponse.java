package com.nubeero.cia.claims.dto;

import com.nubeero.cia.claims.ClaimDocumentType;

import java.time.Instant;
import java.util.UUID;

/**
 * Per-claim required-document checklist row (B12).
 *
 * <p>Derived at request time from
 *   {@code claim_document_requirements} (per-product setup) +
 *   {@code claim_documents}            (already uploaded)
 *
 * <p>{@code received} is true when at least one non-deleted ClaimDocument
 * with a matching {@code documentType} exists for the claim. {@code mappable}
 * is false when the requirement carries no documentType — frontend should
 * treat such rows as informational and not as auto-trackable.
 */
public record ClaimRequiredDocumentResponse(
        UUID requirementId,
        String documentName,
        boolean mandatory,
        ClaimDocumentType documentType,
        boolean mappable,
        boolean received,
        UUID documentId,
        String fileName,
        Instant receivedAt
) {}
