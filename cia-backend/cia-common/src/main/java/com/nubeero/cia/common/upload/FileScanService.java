package com.nubeero.cia.common.upload;

import org.springframework.web.multipart.MultipartFile;

/**
 * Pluggable virus/malware scan hook (CLAUDE.md "virus scan on upload (configurable)").
 * Implementations throw {@link com.nubeero.cia.common.exception.FileValidationException}
 * on a positive detection. Active impl selected by {@code cia.upload.scan.provider}.
 */
public interface FileScanService {
    void scan(MultipartFile file);
}
