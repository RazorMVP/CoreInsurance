package com.nubeero.cia.common.upload;

import com.nubeero.cia.common.exception.FileValidationException;
import java.io.IOException;
import java.io.InputStream;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

/**
 * Server-side upload guard, applied at every upload site BEFORE streaming to storage:
 * not-empty → size ≤ policy cap → declared content-type in allowlist → magic-byte sniff
 * (spoof defence) → pluggable scan. Throws {@link FileValidationException} (422) on any
 * violation. The servlet-level {@code max-file-size} (→ 413) is a separate outer net.
 */
@Component
public class FileUploadValidator {

    private final FileScanService fileScanService;

    public FileUploadValidator(FileScanService fileScanService) {
        this.fileScanService = fileScanService;
    }

    public void validate(MultipartFile file, FileUploadPolicy policy) {
        if (file == null || file.isEmpty()) {
            throw new FileValidationException("EMPTY_FILE",
                    "The uploaded " + policy.label() + " is empty");
        }
        if (file.getSize() > policy.maxSizeBytes()) {
            throw new FileValidationException("FILE_TOO_LARGE",
                    policy.label() + " exceeds the " + (policy.maxSizeBytes() / (1024 * 1024))
                            + " MB limit");
        }
        String contentType = file.getContentType();
        if (contentType == null || !policy.allowedContentTypes().contains(contentType)) {
            throw new FileValidationException("UNSUPPORTED_FILE_TYPE",
                    policy.label() + " type '" + contentType + "' is not allowed (accepted: "
                            + String.join(", ", policy.allowedContentTypes()) + ")");
        }
        byte[] head = readHead(file);
        if (!FileSignatures.matches(contentType, head)) {
            throw new FileValidationException("FILE_CONTENT_MISMATCH",
                    policy.label() + " content does not match its declared type '" + contentType + "'");
        }
        fileScanService.scan(file);
    }

    private byte[] readHead(MultipartFile file) {
        try (InputStream in = file.getInputStream()) {
            byte[] buf = new byte[FileSignatures.MAX_PREFIX];
            int read = in.readNBytes(buf, 0, buf.length);
            if (read == buf.length) return buf;
            byte[] trimmed = new byte[read];
            System.arraycopy(buf, 0, trimmed, 0, read);
            return trimmed;
        } catch (IOException e) {
            throw new FileValidationException("FILE_UNREADABLE",
                    "Could not read the uploaded "
                            + (file.getOriginalFilename() != null ? file.getOriginalFilename() : "file"));
        }
    }
}
