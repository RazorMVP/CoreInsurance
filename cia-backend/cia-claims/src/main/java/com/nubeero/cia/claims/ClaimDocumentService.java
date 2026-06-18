package com.nubeero.cia.claims;

import com.nubeero.cia.common.exception.BusinessRuleException;
import com.nubeero.cia.common.exception.ResourceNotFoundException;
import com.nubeero.cia.common.tenant.TenantContext;
import com.nubeero.cia.storage.DocumentStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ClaimDocumentService {

    private final ClaimDocumentRepository documentRepository;
    private final ClaimRepository         claimRepository;
    private final DocumentStorageService  storageService;
    private final com.nubeero.cia.common.upload.FileUploadValidator fileUploadValidator;

    /** Claim attachments — images or PDF, max 10 MB. */
    private static final com.nubeero.cia.common.upload.FileUploadPolicy CLAIM_DOC_POLICY =
            com.nubeero.cia.common.upload.FileUploadPolicy.imagesAndPdf(
                    "claim document", com.nubeero.cia.common.upload.FileUploadPolicy.mb(10));

    public Page<ClaimDocument> findByClaimId(UUID claimId, Pageable pageable) {
        return documentRepository.findAllByClaim_IdAndDeletedAtIsNull(claimId, pageable);
    }

    /** Filtered list (e.g. only SURVEY_REPORT for the inspection tab). */
    public Page<ClaimDocument> findByClaimIdAndType(UUID claimId, ClaimDocumentType documentType, Pageable pageable) {
        return documentRepository.findAllByClaim_IdAndDocumentTypeAndDeletedAtIsNull(claimId, documentType, pageable);
    }

    public ClaimDocument findOrThrow(UUID id) {
        return documentRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new ResourceNotFoundException("ClaimDocument", id));
    }

    /**
     * Stream a single document by id. Caller must verify the document belongs
     * to the expected claim (path-variable mismatch is enforced upstream).
     */
    public DocumentDownload streamDocument(UUID claimId, UUID documentId) {
        ClaimDocument doc = findOrThrow(documentId);
        if (!doc.getClaim().getId().equals(claimId)) {
            throw new BusinessRuleException("DOCUMENT_NOT_FOUND",
                    "Document does not belong to claim " + claimId);
        }
        InputStream stream = storageService.download(TenantContext.getTenantId(), doc.getFilePath());
        return new DocumentDownload(stream, doc.getFileName());
    }

    /**
     * Zip every {@link ClaimDocumentType#SURVEY_REPORT} document for the claim
     * into a single download. Loads each file into memory; in production with
     * large bundles, this would stream into a {@code StreamingResponseBody}
     * to keep memory bounded — for now, claim documents are small PDFs.
     */
    public DocumentDownload streamInspectionBundle(UUID claimId) {
        Claim claim = claimRepository.findByIdAndDeletedAtIsNull(claimId)
                .orElseThrow(() -> new ResourceNotFoundException("Claim", claimId));

        List<ClaimDocument> reports = documentRepository
                .findAllByClaim_IdAndDocumentTypeAndDeletedAtIsNull(claimId, ClaimDocumentType.SURVEY_REPORT);

        if (reports.isEmpty()) {
            throw new BusinessRuleException("NO_SURVEY_REPORTS",
                    "No survey reports uploaded for this claim yet");
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            String tenantId = TenantContext.getTenantId();
            for (ClaimDocument doc : reports) {
                zos.putNextEntry(new ZipEntry(doc.getFileName()));
                try (InputStream in = storageService.download(tenantId, doc.getFilePath())) {
                    in.transferTo(zos);
                }
                zos.closeEntry();
            }
        } catch (IOException e) {
            throw new BusinessRuleException("BUNDLE_FAILED",
                    "Failed to compose inspection bundle: " + e.getMessage());
        }

        String filename = (claim.getClaimNumber() != null ? claim.getClaimNumber() : claimId.toString())
                + "-survey-reports.zip";
        return new DocumentDownload(new java.io.ByteArrayInputStream(baos.toByteArray()), filename);
    }

    /** Download payload — content stream + suggested filename. */
    public record DocumentDownload(InputStream content, String filename) {}

    /**
     * Upload a claim document end-to-end: stream the bytes to object storage
     * (under {@code claims/{claimId}/{uuid}-{originalFilename}}), then persist
     * a {@link ClaimDocument} pointing at the resulting storage key.
     *
     * <p>The original filename is preserved on the row for display; the storage
     * key prepends a UUID so collisions are impossible. File size is read from
     * the multipart part rather than trusted from the client.
     */
    @Transactional
    public ClaimDocument upload(UUID claimId, ClaimDocumentType documentType, MultipartFile file)
            throws IOException {
        // Validates empty + size + MIME allowlist + magic-byte signature (and runs the scan hook).
        fileUploadValidator.validate(file, CLAIM_DOC_POLICY);

        Claim claim = claimRepository.findByIdAndDeletedAtIsNull(claimId)
                .orElseThrow(() -> new ResourceNotFoundException("Claim", claimId));

        if (claim.getStatus() == ClaimStatus.WITHDRAWN
                || claim.getStatus() == ClaimStatus.REJECTED) {
            throw new BusinessRuleException("INVALID_STATUS",
                    "Cannot upload documents to a " + claim.getStatus() + " claim");
        }

        String originalName = file.getOriginalFilename();
        if (originalName == null || originalName.isBlank()) originalName = "upload";
        String safeName = originalName.replaceAll("[^A-Za-z0-9._-]", "_");
        String path = "claims/" + claimId + "/" + UUID.randomUUID() + "-" + safeName;

        String contentType = file.getContentType();
        if (contentType == null || contentType.isBlank()) contentType = "application/octet-stream";

        storageService.upload(TenantContext.getTenantId(), path, file.getInputStream(), contentType);

        ClaimDocument doc = ClaimDocument.builder()
                .claim(claim)
                .documentType(documentType)
                .fileName(originalName)
                .filePath(path)
                .fileSize(file.getSize())
                .uploadedBy(currentUser())
                .build();

        return documentRepository.save(doc);
    }

    @Transactional
    public void delete(UUID claimId, UUID documentId) {
        ClaimDocument doc = findOrThrow(documentId);
        if (!doc.getClaim().getId().equals(claimId)) {
            throw new BusinessRuleException("DOCUMENT_NOT_FOUND",
                    "Document does not belong to claim " + claimId);
        }
        doc.softDelete();
        documentRepository.save(doc);
    }

    private String currentUser() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null ? auth.getName() : "system";
    }
}
