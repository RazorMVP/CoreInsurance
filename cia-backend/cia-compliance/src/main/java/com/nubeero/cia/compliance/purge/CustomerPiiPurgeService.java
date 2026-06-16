package com.nubeero.cia.compliance.purge;

import com.nubeero.cia.storage.DocumentStorageService;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Anonymizes one customer's master PII: delete blobs → anonymize rows → delete directors/docs → audit. */
@Service
@Slf4j
@RequiredArgsConstructor
public class CustomerPiiPurgeService {

    private final CustomerPurgeRepository repo;
    private final DocumentStorageService storage;
    private final PurgeAuditWriter auditWriter;

    /** Anonymize one customer. Returns true if a row was anonymized (false = already purged, no-op). */
    @Transactional
    public boolean purgeCustomer(String tenantId, UUID customerId, int retentionDays) {
        List<String> blobPaths = repo.blobPathsFor(customerId);
        int blobsDeleted = 0;
        for (String path : blobPaths) {
            try {
                storage.delete(tenantId, path);
                blobsDeleted++;
            } catch (RuntimeException ex) {
                log.warn("PII purge: blob delete failed for customer {} (continuing): {}",
                        customerId, ex.getMessage());
            }
        }
        int anonymized = repo.anonymizeCustomer(customerId);
        if (anonymized == 0) {
            return false; // already purged between eligibility scan and now — idempotent no-op
        }
        int directorsDeleted = repo.deleteDirectors(customerId);
        repo.deleteDocuments(customerId);
        try {
            auditWriter.write(customerId, retentionDays, directorsDeleted, blobsDeleted);
        } catch (RuntimeException ex) {
            log.warn("PII purge: audit write failed for customer {} (purge stands): {}",
                    customerId, ex.getMessage());
        }
        return true;
    }
}
