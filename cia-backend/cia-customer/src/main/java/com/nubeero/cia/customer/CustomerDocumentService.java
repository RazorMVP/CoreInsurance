package com.nubeero.cia.customer;

import com.nubeero.cia.common.audit.AuditAction;
import com.nubeero.cia.common.audit.AuditService;
import com.nubeero.cia.common.exception.ResourceNotFoundException;
import com.nubeero.cia.customer.dto.CustomerDocumentRequest;
import com.nubeero.cia.customer.dto.CustomerDocumentResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CustomerDocumentService {

    private final CustomerService customerService;
    private final CustomerDocumentRepository repository;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public List<CustomerDocumentResponse> list(UUID customerId) {
        customerService.findOrThrow(customerId);
        return repository.findAllByCustomerIdAndDeletedAtIsNull(customerId)
                .stream().map(this::toResponse).toList();
    }

    @Transactional
    public CustomerDocumentResponse add(UUID customerId, CustomerDocumentRequest request) {
        Customer customer = customerService.findOrThrow(customerId);
        CustomerDocument doc = CustomerDocument.builder()
                .customer(customer)
                .documentType(request.getDocumentType())
                .documentName(request.getDocumentName())
                .documentPath(request.getDocumentPath())
                .mimeType(request.getMimeType())
                .fileSizeBytes(request.getFileSizeBytes())
                .uploadedBy(currentUserId())
                .build();
        CustomerDocument saved = repository.save(doc);
        auditService.log("CustomerDocument", saved.getId().toString(), AuditAction.CREATE, null, saved);
        return toResponse(saved);
    }

    @Transactional
    public void delete(UUID customerId, UUID documentId) {
        customerService.findOrThrow(customerId);
        CustomerDocument doc = repository.findById(documentId)
                .filter(d -> d.getDeletedAt() == null && d.getCustomer().getId().equals(customerId))
                .orElseThrow(() -> new ResourceNotFoundException("CustomerDocument", documentId));
        doc.softDelete();
        repository.save(doc);
        auditService.log("CustomerDocument", documentId.toString(), AuditAction.DELETE, doc, null);
    }

    private String currentUserId() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
            return jwt.getSubject();
        }
        return "system";
    }

    private CustomerDocumentResponse toResponse(CustomerDocument d) {
        return CustomerDocumentResponse.builder()
                .id(d.getId())
                .documentType(d.getDocumentType())
                .documentName(d.getDocumentName())
                .documentPath(d.getDocumentPath())
                .mimeType(d.getMimeType())
                .fileSizeBytes(d.getFileSizeBytes())
                .uploadedBy(d.getUploadedBy())
                .createdAt(d.getCreatedAt())
                .build();
    }
}
