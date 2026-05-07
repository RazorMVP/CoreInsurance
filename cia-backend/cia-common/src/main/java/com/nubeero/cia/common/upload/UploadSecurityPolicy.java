package com.nubeero.cia.common.upload;

import com.nubeero.cia.common.exception.BusinessRuleException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class UploadSecurityPolicy {

    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "application/pdf",
            "image/jpeg",
            "image/png"
    );

    private static final Map<String, Set<String>> EXTENSIONS_BY_TYPE = Map.of(
            "application/pdf", Set.of(".pdf"),
            "image/jpeg", Set.of(".jpg", ".jpeg"),
            "image/png", Set.of(".png")
    );

    private final MalwareScanner malwareScanner;

    @Value("${cia.upload.kyc-max-bytes:10485760}")
    private long kycMaxBytes;

    @Value("${cia.upload.claim-max-bytes:26214400}")
    private long claimMaxBytes;

    public ValidatedUpload validate(UploadCategory category, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessRuleException("EMPTY_FILE", "Uploaded file is empty");
        }

        String originalName = file.getOriginalFilename();
        if (originalName == null || originalName.isBlank()) {
            originalName = "upload";
        }
        String contentType = normalizeContentType(file.getContentType());
        validateContentType(contentType);
        validateExtension(originalName, contentType);

        try {
            byte[] bytes = file.getBytes();
            validateSize(category, bytes.length);
            MalwareScanResult scanResult = malwareScanner.scan(
                    originalName, contentType, new ByteArrayInputStream(bytes));
            if (!scanResult.clean()) {
                throw new BusinessRuleException("MALWARE_DETECTED",
                        scanResult.reason() != null ? scanResult.reason() : "Uploaded file failed malware scan");
            }
            return new ValidatedUpload(originalName, safeFilename(originalName), contentType, bytes);
        } catch (BusinessRuleException e) {
            throw e;
        } catch (IOException e) {
            throw new BusinessRuleException("UPLOAD_VALIDATION_FAILED",
                    "Uploaded file could not be validated");
        }
    }

    private String normalizeContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            throw new BusinessRuleException("INVALID_FILE_TYPE", "Uploaded file content type is required");
        }
        return contentType.toLowerCase(Locale.ROOT);
    }

    private void validateContentType(String contentType) {
        if (!ALLOWED_CONTENT_TYPES.contains(contentType)) {
            throw new BusinessRuleException("INVALID_FILE_TYPE",
                    "Only PDF, JPEG, and PNG uploads are allowed");
        }
    }

    private void validateExtension(String filename, String contentType) {
        String lower = filename.toLowerCase(Locale.ROOT);
        Set<String> allowedExtensions = EXTENSIONS_BY_TYPE.get(contentType);
        boolean allowed = allowedExtensions != null
                && allowedExtensions.stream().anyMatch(lower::endsWith);
        if (!allowed) {
            throw new BusinessRuleException("INVALID_FILE_EXTENSION",
                    "File extension does not match the declared content type");
        }
    }

    private void validateSize(UploadCategory category, long size) {
        long maxBytes = category == UploadCategory.KYC_DOCUMENT ? kycMaxBytes : claimMaxBytes;
        if (size > maxBytes) {
            throw new BusinessRuleException("FILE_TOO_LARGE",
                    "Uploaded file exceeds the configured size limit");
        }
    }

    private String safeFilename(String filename) {
        return filename.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
