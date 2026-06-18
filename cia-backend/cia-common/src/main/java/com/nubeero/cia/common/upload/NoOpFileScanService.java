package com.nubeero.cia.common.upload;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

/**
 * Default scan impl — does nothing (clean). A real ClamAV/API impl registers under a
 * different {@code cia.upload.scan.provider} value. matchIfMissing so uploads work
 * out-of-the-box with no scanner configured.
 */
@Component
@ConditionalOnProperty(name = "cia.upload.scan.provider", havingValue = "none", matchIfMissing = true)
public class NoOpFileScanService implements FileScanService {
    @Override
    public void scan(MultipartFile file) {
        // no-op
    }
}
