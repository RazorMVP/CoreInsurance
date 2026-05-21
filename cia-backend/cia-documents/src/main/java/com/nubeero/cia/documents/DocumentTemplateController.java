package com.nubeero.cia.documents;

import com.nubeero.cia.common.api.ApiResponse;
import com.nubeero.cia.documents.dto.DocumentTemplateResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/document-templates")
@Tag(name = "Document Templates",
     description = "PDF template master data for policy / quote / endorsement / DV documents. Per-product or per-class-of-business scoping. Rendered server-side via Thymeleaf + Apache PDFBox at document generation time.")
@SecurityRequirement(name = "bearer-jwt")
@RequiredArgsConstructor
public class DocumentTemplateController {

    private final DocumentTemplateService service;

    @GetMapping
    @PreAuthorize("hasRole('SETUP_VIEW')")
    @Operation(summary = "List document templates (paginated)")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Template page",
            content = @Content(schema = @Schema(implementation = DocumentTemplateResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden", content = @Content)
    })
    public ApiResponse<List<DocumentTemplateResponse>> list(
            @PageableDefault(size = 20) Pageable pageable) {
        return ApiResponse.success(service.list(pageable).map(this::toResponse).getContent());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('SETUP_VIEW')")
    @Operation(summary = "Get template metadata by id")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Template found",
            content = @Content(schema = @Schema(implementation = DocumentTemplateResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Template not found", content = @Content)
    })
    public ApiResponse<DocumentTemplateResponse> get(@PathVariable UUID id) {
        return ApiResponse.success(toResponse(service.findOrThrow(id)));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('SETUP_UPDATE')")
    @Operation(summary = "Upload a new document template",
               description = "Multipart upload. templateType is mandatory (POLICY / QUOTE / ENDORSEMENT / DV). Optional scope via productId and/or classOfBusinessId — null = global default template for the type. File is stored via DocumentStorageService.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Template uploaded",
            content = @Content(schema = @Schema(implementation = DocumentTemplateResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "templateType missing or file invalid", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden", content = @Content)
    })
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
    @Operation(summary = "Soft-delete a template",
               description = "Historical documents generated with this template remain unaffected — they are stored as rendered bytes via DocumentStorageService.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "Template deleted"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Forbidden", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Template not found", content = @Content)
    })
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
