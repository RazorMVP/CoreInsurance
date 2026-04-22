package com.nubeero.cia.documents;

import com.nubeero.cia.common.exception.ResourceNotFoundException;
import com.nubeero.cia.storage.DocumentStorageService;
import com.nubeero.cia.common.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DocumentTemplateService {

    private final DocumentTemplateRepository repository;
    private final DocumentStorageService storageService;

    @Transactional
    public DocumentTemplate upload(DocumentTemplateType type, UUID productId,
                                    UUID classOfBusinessId, String description,
                                    MultipartFile file) throws IOException {
        // Deactivate any existing active templates for the same type+scope
        repository.findBestMatch(type, productId, classOfBusinessId).stream()
                .filter(t -> productId == null ? t.getProductId() == null : productId.equals(t.getProductId()))
                .filter(t -> classOfBusinessId == null ? t.getClassOfBusinessId() == null
                        : classOfBusinessId.equals(t.getClassOfBusinessId()))
                .forEach(t -> { t.setActive(false); repository.save(t); });

        String path = "templates/" + type.name().toLowerCase() + "/" + UUID.randomUUID() + ".html";
        storageService.upload(TenantContext.getTenantId(), path,
                file.getInputStream(), "text/html");

        DocumentTemplate template = DocumentTemplate.builder()
                .templateType(type)
                .productId(productId)
                .classOfBusinessId(classOfBusinessId)
                .storagePath(path)
                .description(description)
                .active(true)
                .build();

        return repository.save(template);
    }

    public DocumentTemplate findOrThrow(UUID id) {
        return repository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new ResourceNotFoundException("DocumentTemplate", id));
    }

    public Page<DocumentTemplate> list(Pageable pageable) {
        return repository.findAllByDeletedAtIsNull(pageable);
    }

    @Transactional
    public void delete(UUID id) {
        DocumentTemplate template = findOrThrow(id);
        template.softDelete();
        repository.save(template);
    }
}
