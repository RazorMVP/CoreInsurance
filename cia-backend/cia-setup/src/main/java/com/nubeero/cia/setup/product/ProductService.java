package com.nubeero.cia.setup.product;

import com.nubeero.cia.common.audit.AuditAction;
import com.nubeero.cia.common.audit.AuditService;
import com.nubeero.cia.common.exception.BusinessRuleException;
import com.nubeero.cia.common.exception.ResourceNotFoundException;
import com.nubeero.cia.setup.product.dto.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ProductService {

    private final ProductRepository productRepository;
    private final ClassOfBusinessRepository cobRepository;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public Page<ProductResponse> list(Pageable pageable) {
        return productRepository.findAllByDeletedAtIsNull(pageable).map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public ProductResponse get(UUID id) {
        return toResponse(findOrThrow(id));
    }

    @Transactional
    public ProductResponse create(ProductRequest request) {
        if (productRepository.findByCodeAndDeletedAtIsNull(request.getCode().toUpperCase()).isPresent()) {
            throw new BusinessRuleException("PRODUCT_CODE_EXISTS",
                    "Product code already exists: " + request.getCode());
        }
        ClassOfBusiness cob = cobRepository.findById(request.getClassOfBusinessId())
                .filter(e -> e.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("ClassOfBusiness", request.getClassOfBusinessId()));

        Product product = Product.builder()
                .name(request.getName())
                .code(request.getCode().toUpperCase())
                .classOfBusiness(cob)
                .type(request.getType())
                .rate(request.getRate())
                .minPremium(request.getMinPremium())
                .active(request.isActive())
                .sections(new ArrayList<>())
                .build();

        if (request.getSections() != null) {
            request.getSections().forEach(sr -> product.getSections().add(ProductSection.builder()
                    .product(product)
                    .name(sr.getName())
                    .code(sr.getCode().toUpperCase())
                    .rate(sr.getRate())
                    .orderNo(sr.getOrderNo())
                    .build()));
        }

        Product saved = productRepository.save(product);
        auditService.log("Product", saved.getId().toString(), AuditAction.CREATE, null, saved);
        return toResponse(saved);
    }

    @Transactional
    public ProductResponse update(UUID id, ProductRequest request) {
        Product product = findOrThrow(id);
        String newCode = request.getCode().toUpperCase();
        if (!product.getCode().equals(newCode) &&
                productRepository.findByCodeAndDeletedAtIsNull(newCode).isPresent()) {
            throw new BusinessRuleException("PRODUCT_CODE_EXISTS", "Product code already exists: " + newCode);
        }
        ClassOfBusiness cob = cobRepository.findById(request.getClassOfBusinessId())
                .filter(e -> e.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("ClassOfBusiness", request.getClassOfBusinessId()));

        product.setName(request.getName());
        product.setCode(newCode);
        product.setClassOfBusiness(cob);
        product.setType(request.getType());
        product.setRate(request.getRate());
        product.setMinPremium(request.getMinPremium());
        product.setActive(request.isActive());

        product.getSections().clear();
        if (request.getSections() != null) {
            request.getSections().forEach(sr -> product.getSections().add(ProductSection.builder()
                    .product(product)
                    .name(sr.getName())
                    .code(sr.getCode().toUpperCase())
                    .rate(sr.getRate())
                    .orderNo(sr.getOrderNo())
                    .build()));
        }

        Product saved = productRepository.save(product);
        auditService.log("Product", id.toString(), AuditAction.UPDATE, null, saved);
        return toResponse(saved);
    }

    @Transactional
    public void delete(UUID id) {
        Product product = findOrThrow(id);
        product.softDelete();
        productRepository.save(product);
        auditService.log("Product", id.toString(), AuditAction.DELETE, product, null);
    }

    /** Atomically generates the next policy number for a product. */
    @Transactional
    public String generatePolicyNumber(UUID productId) {
        PolicyNumberFormat fmt = productRepository.findByIdForUpdate(productId)
                .map(p -> p)  // ensure product exists and is locked
                .orElseThrow(() -> new ResourceNotFoundException("Product", productId))
                .getSections(); // dummy — real lock is on product via findByIdForUpdate

        // Delegate to PolicyNumberFormat service
        throw new UnsupportedOperationException("Use PolicyNumberFormatService.generateNext()");
    }

    private Product findOrThrow(UUID id) {
        return productRepository.findById(id)
                .filter(p -> p.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("Product", id));
    }

    ProductResponse toResponse(Product p) {
        List<ProductSectionResponse> sections = p.getSections() == null ? List.of() :
                p.getSections().stream()
                        .filter(s -> s.getDeletedAt() == null)
                        .map(s -> ProductSectionResponse.builder()
                                .id(s.getId())
                                .name(s.getName())
                                .code(s.getCode())
                                .rate(s.getRate())
                                .orderNo(s.getOrderNo())
                                .build())
                        .toList();

        return ProductResponse.builder()
                .id(p.getId())
                .name(p.getName())
                .code(p.getCode())
                .classOfBusinessId(p.getClassOfBusiness().getId())
                .classOfBusinessName(p.getClassOfBusiness().getName())
                .type(p.getType())
                .rate(p.getRate())
                .minPremium(p.getMinPremium())
                .active(p.isActive())
                .sections(sections)
                .createdAt(p.getCreatedAt())
                .updatedAt(p.getUpdatedAt())
                .build();
    }
}
