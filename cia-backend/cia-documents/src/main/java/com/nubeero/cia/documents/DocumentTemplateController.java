package com.nubeero.cia.documents;

import com.nubeero.cia.common.api.ApiResponse;
import com.nubeero.cia.documents.dto.DocumentTemplateResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/document-templates")
@RequiredArgsConstructor
public class DocumentTemplateController {

    private final DocumentTemplateService service;

    @GetMapping
    @PreAuthorize("hasRole('SETUP_VIEW')")
    public ApiResponse<Page<DocumentTemplateResponse>> list(
            @PageableDefault(size = 20) Pageable pageable) {
        return ApiResponse.success(service.list(pageable).map(this::toResponse));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('SETUP_VIEW')")
    public ApiResponse<DocumentTemplateResponse> get(@PathVariable UUID id) {
        return ApiResponse.success(toResponse(service.findOrThrow(id)));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('SETUP_UPDATE')")
    public ApiResponse<DocumentTemplateResponse> upload(
            @RequestParam DocumentTemplateType templateType,
            @RequestParam(required = false) UUID productId,
            @RequestParam(required = false) UUID classOfBusinessId,
            @RequestParam(required = false) String description,
            @RequestParam("file") MultipartFile file) throws IOException {
        return ApiResponse.success(toResponse(
                service.upload(templateType, productId, classOfBusinessId, description, file)));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('SETUP_UPDATE')")
    public void delete(@PathVariable UUID id) {
        service.delete(id);
    }

    // ─── Mapping ──────────────────────────────────────────────────────────

    private DocumentTemplateResponse toResponse(DocumentTemplate t) {
        return new DocumentTemplateResponse(
                t.getId(), t.getTemplateType(), t.getProductId(), t.getClassOfBusinessId(),
                t.getStoragePath(), t.getDescription(), t.isActive(), t.getCreatedAt()
        );
    }
}
