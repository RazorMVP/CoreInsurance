package com.nubeero.cia.documents;

/**
 * Generates PDF documents for policy certificates, endorsements, and claim DVs,
 * stores them in MinIO, and returns the storage path for persisting on the entity.
 *
 * Implementations must never throw — log and return null on failure so the
 * calling approval flow is never blocked by document generation.
 */
public interface DocumentGenerationService {

    String generatePolicyDocument(PolicyDocumentContext ctx);

    String generateEndorsementDocument(EndorsementDocumentContext ctx);

    String generateClaimDv(ClaimDvContext ctx);
}
