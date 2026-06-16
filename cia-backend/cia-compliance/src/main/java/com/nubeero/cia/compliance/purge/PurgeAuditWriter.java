package com.nubeero.cia.compliance.purge;

import com.nubeero.cia.common.audit.AuditAction;
import com.nubeero.cia.common.audit.AuditService;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Writes the metadata-only retention-purge audit row in its OWN transaction (design §6.5). */
@Component
@Slf4j
@RequiredArgsConstructor
public class PurgeAuditWriter {

    private final AuditService audit;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void write(UUID customerId, int retentionDays, int directorsDeleted, int blobsDeleted) {
        audit.logWithReason("Customer", customerId.toString(), AuditAction.DELETE, null,
            Map.of("customerId", customerId.toString(),
                   "retentionDays", retentionDays,
                   "directorsDeleted", directorsDeleted,
                   "blobsDeleted", blobsDeleted,
                   "purgedAt", Instant.now().toString()),
            "NDPR_RETENTION_PURGE");
    }
}
