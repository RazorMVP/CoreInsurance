package com.nubeero.cia.customer;

import com.nubeero.cia.common.audit.AuditAction;
import com.nubeero.cia.common.audit.AuditService;
import com.nubeero.cia.common.exception.BusinessRuleException;
import com.nubeero.cia.common.exception.ResourceNotFoundException;
import com.nubeero.cia.common.tenant.TenantContext;
import com.nubeero.cia.customer.dto.*;
import com.nubeero.cia.integrations.kyc.*;
import com.nubeero.cia.setup.customer.CustomerNumberFormatService;
import com.nubeero.cia.storage.DocumentStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class CustomerService {

    private final CustomerRepository repository;
    private final CustomerDirectorRepository directorRepository;
    private final KycVerificationService kycVerificationService;
    private final AuditService auditService;
    private final DocumentStorageService documentStorageService;
    private final CustomerNumberFormatService customerNumberFormatService;

    // ─── Queries ────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Page<CustomerSummaryResponse> list(CustomerType type, KycStatus kycStatus, Pageable pageable) {
        Page<Customer> page;
        if (type != null) {
            page = repository.findAllByCustomerTypeAndDeletedAtIsNull(type, pageable);
        } else if (kycStatus != null) {
            page = repository.findAllByKycStatusAndDeletedAtIsNull(kycStatus, pageable);
        } else {
            page = repository.findAllByDeletedAtIsNull(pageable);
        }
        return page.map(this::toSummary);
    }

    @Transactional(readOnly = true)
    public Page<CustomerSummaryResponse> search(String query, Pageable pageable) {
        return repository.search(query, pageable).map(this::toSummary);
    }

    @Transactional(readOnly = true)
    public CustomerResponse get(UUID id) {
        return toResponse(findOrThrow(id));
    }

    // ─── Create ─────────────────────────────────────────────────────

    @Transactional
    public CustomerResponse createIndividual(IndividualCustomerRequest request,
                                              MultipartFile idDocument) {
        validateExpiryDate(request.getIdType(), request.getIdExpiryDate(), "ID document");

        Customer customer = Customer.builder()
                .customerType(CustomerType.INDIVIDUAL)
                .customerNumber(customerNumberFormatService.generateNext("INDIVIDUAL"))
                .firstName(request.getFirstName())
                .lastName(request.getLastName())
                .otherNames(request.getOtherNames())
                .dateOfBirth(request.getDateOfBirth())
                .gender(request.getGender())
                .maritalStatus(request.getMaritalStatus())
                .idType(request.getIdType())
                .idNumber(request.getIdNumber())
                .idExpiryDate(request.getIdExpiryDate())
                .email(request.getEmail())
                .phone(request.getPhone())
                .alternatePhone(request.getAlternatePhone())
                .address(request.getAddress())
                .city(request.getCity())
                .state(request.getState())
                .country(request.getCountry())
                .build();

        // Save first to get ID, then upload document
        runIndividualKyc(customer);
        Customer saved = repository.save(customer);

        String docUrl = uploadKycDocument(idDocument, saved.getId(), "kyc-id");
        saved.setIdDocumentUrl(docUrl);
        saved = repository.save(saved);

        auditService.log("Customer", saved.getId().toString(), AuditAction.CREATE, null, saved);
        return toResponse(saved);
    }

    @Transactional
    public CustomerResponse createCorporate(CorporateCustomerRequest request,
                                             MultipartFile cacCertificate,
                                             List<MultipartFile> directorIdDocuments) {
        if (request.getCacIssuedDate() == null) {
            throw new BusinessRuleException("MISSING_CAC_DATE", "CAC certificate issued date is required");
        }
        // Validate each director's expiry date
        List<CustomerDirectorRequest> dirs = request.getDirectors();
        for (int i = 0; i < dirs.size(); i++) {
            CustomerDirectorRequest dir = dirs.get(i);
            validateExpiryDate(dir.getIdType(), dir.getIdExpiryDate(), "Director " + (i + 1) + " ID document");
        }

        Customer customer = Customer.builder()
                .customerType(CustomerType.CORPORATE)
                .customerNumber(customerNumberFormatService.generateNext("CORPORATE"))
                .companyName(request.getCompanyName())
                .rcNumber(request.getRcNumber())
                .cacIssuedDate(request.getCacIssuedDate())
                .incorporationDate(request.getIncorporationDate())
                .industry(request.getIndustry())
                .contactPerson(request.getContactPerson())
                .email(request.getEmail())
                .phone(request.getPhone())
                .alternatePhone(request.getAlternatePhone())
                .address(request.getAddress())
                .city(request.getCity())
                .state(request.getState())
                .country(request.getCountry())
                .build();

        runCorporateKyc(customer, request.getDirectors());
        Customer saved = repository.save(customer);

        // Upload CAC certificate
        String cacUrl = uploadKycDocument(cacCertificate, saved.getId(), "cac-certificate");
        saved.setCacCertificateUrl(cacUrl);

        // Upload director ID documents (indexed by position)
        for (int i = 0; i < saved.getDirectors().size(); i++) {
            if (directorIdDocuments != null && i < directorIdDocuments.size()) {
                MultipartFile dirDoc = directorIdDocuments.get(i);
                if (dirDoc != null && !dirDoc.isEmpty()) {
                    String dirDocUrl = uploadKycDocument(dirDoc, saved.getId(), "director-" + i + "-id");
                    CustomerDirector dir = saved.getDirectors().get(i);
                    dir.setIdDocumentUrl(dirDocUrl);
                    dir.setIdExpiryDate(dirs.get(i).getIdExpiryDate());
                }
            }
        }

        saved = repository.save(saved);
        auditService.log("Customer", saved.getId().toString(), AuditAction.CREATE, null, saved);
        return toResponse(saved);
    }

    // ─── Update ─────────────────────────────────────────────────────

    @Transactional
    public CustomerResponse update(UUID id, CustomerUpdateRequest request,
                                   MultipartFile idDocument,
                                   java.util.Map<String, MultipartFile> directorDocs) {
        Customer customer = findOrThrow(id);
        CustomerResponse oldSnapshot = toResponse(customer);

        boolean kycChanged = isKycChanged(customer, request, idDocument);

        if (kycChanged && (request.getKycUpdateReason() == null || request.getKycUpdateReason().isBlank())) {
            throw new BusinessRuleException("KYC_UPDATE_REASON_REQUIRED",
                    "A reason must be provided when updating KYC details.");
        }

        if (customer.getCustomerType() == CustomerType.INDIVIDUAL) {
            applyIndividualUpdate(customer, request);
        } else {
            applyCorporateUpdate(customer, request);
        }

        if (kycChanged) {
            if (request.getIdType() != null)       customer.setIdType(request.getIdType());
            if (request.getIdNumber() != null)     customer.setIdNumber(request.getIdNumber());
            if (request.getIdExpiryDate() != null) customer.setIdExpiryDate(request.getIdExpiryDate());

            if (idDocument != null && !idDocument.isEmpty()) {
                String docUrl = uploadKycDocument(idDocument, customer.getId(), "kyc-id");
                customer.setIdDocumentUrl(docUrl);
            }

            validateExpiryDate(customer.getIdType(), customer.getIdExpiryDate(), "ID document");
            runIndividualKyc(customer);
        }

        // Process director changes (corporate only)
        if (customer.getCustomerType() == CustomerType.CORPORATE
                && request.getDirectors() != null
                && !request.getDirectors().isEmpty()) {
            processDirectorUpdates(customer, request.getDirectors(), directorDocs);

            long activeCount = customer.getDirectors().stream()
                    .filter(d -> d.getDeletedAt() == null)
                    .count();
            if (activeCount < 2) {
                throw new BusinessRuleException("MINIMUM_DIRECTORS_REQUIRED",
                        "At least 2 active directors are required for a corporate customer.");
            }
        }

        Customer saved = repository.save(customer);

        // Audit: general contact update (before → after)
        auditService.log("Customer", id.toString(), AuditAction.UPDATE, oldSnapshot, toResponse(saved));

        // Audit: dedicated KYC update entry with reason
        if (kycChanged) {
            var kycAudit = java.util.Map.of(
                    "reason", request.getKycUpdateReason(),
                    "notes",  request.getKycUpdateNotes() != null ? request.getKycUpdateNotes() : "",
                    "newIdType",   saved.getIdType() != null ? saved.getIdType().name() : "",
                    "newIdNumber", saved.getIdNumber() != null ? saved.getIdNumber() : "",
                    "kycStatus",   saved.getKycStatus().name()
            );
            auditService.log("CustomerKyc", id.toString(), AuditAction.UPDATE,
                    java.util.Map.of(
                            "idType",   oldSnapshot.getIdType() != null ? oldSnapshot.getIdType().name() : "",
                            "idNumber", oldSnapshot.getIdNumber() != null ? oldSnapshot.getIdNumber() : "",
                            "kycStatus", oldSnapshot.getKycStatus().name()
                    ),
                    kycAudit);
        }

        return toResponse(saved);
    }

    private void processDirectorUpdates(Customer customer,
                                        List<com.nubeero.cia.customer.dto.DirectorUpdateRequest> requests,
                                        java.util.Map<String, MultipartFile> directorDocs) {
        for (int i = 0; i < requests.size(); i++) {
            com.nubeero.cia.customer.dto.DirectorUpdateRequest req = requests.get(i);
            MultipartFile dirDoc = directorDocs != null ? directorDocs.get("directorDoc_" + i) : null;

            if (req.getId() != null) {
                // Existing director
                CustomerDirector director = customer.getDirectors().stream()
                        .filter(d -> d.getId().equals(req.getId()))
                        .findFirst()
                        .orElseThrow(() -> new BusinessRuleException("DIRECTOR_NOT_FOUND",
                                "Director not found: " + req.getId()));

                if (req.isDeleted()) {
                    director.setDeletedAt(Instant.now());
                    auditService.log("CustomerDirector", director.getId().toString(),
                            AuditAction.DELETE, toDirectorSnapshot(director), null);
                } else {
                    boolean kycChanged = isDirectorKycChanged(director, req, dirDoc);
                    if (kycChanged && (req.getKycUpdateReason() == null || req.getKycUpdateReason().isBlank())) {
                        throw new BusinessRuleException("KYC_UPDATE_REASON_REQUIRED",
                                "Reason required when updating KYC details for director: "
                                        + director.getFirstName() + " " + director.getLastName());
                    }

                    var oldSnapshot = toDirectorSnapshot(director);

                    if (req.getFirstName() != null) director.setFirstName(req.getFirstName());
                    if (req.getLastName()  != null) director.setLastName(req.getLastName());
                    if (req.getDateOfBirth() != null) director.setDateOfBirth(req.getDateOfBirth());

                    if (kycChanged) {
                        if (req.getIdType()       != null) director.setIdType(req.getIdType());
                        if (req.getIdNumber()     != null) director.setIdNumber(req.getIdNumber());
                        if (req.getIdExpiryDate() != null) {
                            validateExpiryDate(req.getIdType() != null ? req.getIdType() : director.getIdType(),
                                    req.getIdExpiryDate(), "Director ID document");
                            director.setIdExpiryDate(req.getIdExpiryDate());
                        }
                        if (dirDoc != null && !dirDoc.isEmpty()) {
                            String docUrl = uploadKycDocument(dirDoc, customer.getId(),
                                    "director-" + director.getId() + "-id");
                            director.setIdDocumentUrl(docUrl);
                        }
                        // Re-verify this director's KYC
                        try {
                            KycResult result = kycVerificationService.verifyDirector(
                                    DirectorKycRequest.builder()
                                            .idType(director.getIdType() != null ? director.getIdType().name() : null)
                                            .idNumber(director.getIdNumber())
                                            .firstName(director.getFirstName())
                                            .lastName(director.getLastName())
                                            .dateOfBirth(director.getDateOfBirth())
                                            .build());
                            director.setKycStatus(result.isVerified() ? KycStatus.PASSED : KycStatus.FAILED);
                            director.setKycProviderRef(result.getVerificationId());
                            director.setKycFailureReason(result.getFailureReason());
                        } catch (Exception e) {
                            director.setKycStatus(KycStatus.FAILED);
                            director.setKycFailureReason("KYC provider unavailable: " + e.getMessage());
                        }

                        auditService.log("CustomerDirectorKyc", director.getId().toString(),
                                AuditAction.UPDATE, oldSnapshot,
                                java.util.Map.of(
                                        "reason", req.getKycUpdateReason(),
                                        "notes",  req.getKycUpdateNotes() != null ? req.getKycUpdateNotes() : "",
                                        "kycStatus", director.getKycStatus().name()
                                ));
                    } else {
                        auditService.log("CustomerDirector", director.getId().toString(),
                                AuditAction.UPDATE, oldSnapshot, toDirectorSnapshot(director));
                    }
                }
            } else {
                // New director — add and verify
                CustomerDirector newDir = CustomerDirector.builder()
                        .customer(customer)
                        .firstName(req.getFirstName())
                        .lastName(req.getLastName())
                        .dateOfBirth(req.getDateOfBirth())
                        .idType(req.getIdType())
                        .idNumber(req.getIdNumber())
                        .idExpiryDate(req.getIdExpiryDate())
                        .build();

                if (req.getIdExpiryDate() != null) {
                    validateExpiryDate(req.getIdType(), req.getIdExpiryDate(), "New director ID document");
                }

                customer.getDirectors().add(newDir);
                // KYC verification runs after save (ID needed for document upload)
                // Document upload deferred — newDir has no ID yet; handled post-save via directorRepository
                auditService.log("CustomerDirector", "new", AuditAction.CREATE, null, newDir);
            }
        }

        // Verify KYC for any newly added directors (those with PENDING status and no provider ref)
        customer.getDirectors().stream()
                .filter(d -> d.getDeletedAt() == null
                        && d.getKycStatus() == KycStatus.PENDING
                        && d.getKycProviderRef() == null)
                .forEach(d -> {
                    try {
                        KycResult result = kycVerificationService.verifyDirector(
                                DirectorKycRequest.builder()
                                        .idType(d.getIdType() != null ? d.getIdType().name() : null)
                                        .idNumber(d.getIdNumber())
                                        .firstName(d.getFirstName())
                                        .lastName(d.getLastName())
                                        .dateOfBirth(d.getDateOfBirth())
                                        .build());
                        d.setKycStatus(result.isVerified() ? KycStatus.PASSED : KycStatus.FAILED);
                        d.setKycProviderRef(result.getVerificationId());
                        d.setKycFailureReason(result.getFailureReason());
                    } catch (Exception e) {
                        d.setKycStatus(KycStatus.FAILED);
                        d.setKycFailureReason("KYC provider unavailable: " + e.getMessage());
                    }
                });
    }

    private boolean isDirectorKycChanged(CustomerDirector director,
                                          com.nubeero.cia.customer.dto.DirectorUpdateRequest req,
                                          MultipartFile dirDoc) {
        if (dirDoc != null && !dirDoc.isEmpty()) return true;
        if (req.getIdType()   != null && !req.getIdType().equals(director.getIdType()))     return true;
        if (req.getIdNumber() != null && !req.getIdNumber().equals(director.getIdNumber())) return true;
        if (req.getIdExpiryDate() != null && !req.getIdExpiryDate().equals(director.getIdExpiryDate())) return true;
        return false;
    }

    private java.util.Map<String, Object> toDirectorSnapshot(CustomerDirector d) {
        return java.util.Map.of(
                "firstName", d.getFirstName() != null ? d.getFirstName() : "",
                "lastName",  d.getLastName()  != null ? d.getLastName()  : "",
                "idType",    d.getIdType()    != null ? d.getIdType().name() : "",
                "idNumber",  d.getIdNumber()  != null ? d.getIdNumber()  : "",
                "kycStatus", d.getKycStatus().name()
        );
    }

    private boolean isKycChanged(Customer customer, CustomerUpdateRequest request, MultipartFile idDocument) {
        if (idDocument != null && !idDocument.isEmpty()) return true;
        if (request.getIdType() != null   && !request.getIdType().equals(customer.getIdType()))     return true;
        if (request.getIdNumber() != null && !request.getIdNumber().equals(customer.getIdNumber())) return true;
        if (request.getIdExpiryDate() != null && !request.getIdExpiryDate().equals(customer.getIdExpiryDate())) return true;
        return false;
    }

    // ─── KYC ────────────────────────────────────────────────────────

    @Transactional
    public CustomerResponse retriggerKyc(UUID id) {
        Customer customer = findOrThrow(id);
        if (customer.getKycStatus() == KycStatus.PASSED) {
            throw new BusinessRuleException("KYC_ALREADY_PASSED", "KYC already verified for customer: " + id);
        }
        if (customer.getCustomerType() == CustomerType.INDIVIDUAL) {
            runIndividualKyc(customer);
        } else {
            List<CustomerDirectorRequest> directorRequests = directorRepository
                    .findAllByCustomerIdAndDeletedAtIsNull(id)
                    .stream()
                    .map(d -> {
                        CustomerDirectorRequest r = new CustomerDirectorRequest();
                        r.setFirstName(d.getFirstName());
                        r.setLastName(d.getLastName());
                        r.setDateOfBirth(d.getDateOfBirth());
                        r.setIdType(d.getIdType());
                        r.setIdNumber(d.getIdNumber());
                        return r;
                    }).toList();
            runCorporateKyc(customer, directorRequests);
        }
        Customer saved = repository.save(customer);
        auditService.log("Customer", id.toString(), AuditAction.UPDATE, null, saved);
        return toResponse(saved);
    }

    // ─── Blacklist ───────────────────────────────────────────────────

    @Transactional
    public CustomerResponse blacklist(UUID id, BlacklistRequest request) {
        Customer customer = findOrThrow(id);
        customer.setCustomerStatus(CustomerStatus.BLACKLISTED);
        customer.setBlacklistReason(request.getReason());
        customer.setBlacklistedAt(Instant.now());
        customer.setBlacklistedBy(currentUserId());
        Customer saved = repository.save(customer);
        auditService.log("Customer", id.toString(), AuditAction.UPDATE, null, saved);
        return toResponse(saved);
    }

    @Transactional
    public CustomerResponse unblacklist(UUID id) {
        Customer customer = findOrThrow(id);
        if (customer.getCustomerStatus() != CustomerStatus.BLACKLISTED) {
            throw new BusinessRuleException("CUSTOMER_NOT_BLACKLISTED", "Customer is not blacklisted: " + id);
        }
        customer.setCustomerStatus(CustomerStatus.ACTIVE);
        customer.setBlacklistReason(null);
        customer.setBlacklistedAt(null);
        customer.setBlacklistedBy(null);
        Customer saved = repository.save(customer);
        auditService.log("Customer", id.toString(), AuditAction.UPDATE, null, saved);
        return toResponse(saved);
    }

    // ─── KYC logic ──────────────────────────────────────────────────

    private void runIndividualKyc(Customer customer) {
        customer.setKycStatus(KycStatus.IN_PROGRESS);
        try {
            KycResult result = kycVerificationService.verifyIndividual(
                    IndividualKycRequest.builder()
                            .idType(customer.getIdType() != null ? customer.getIdType().name() : null)
                            .idNumber(customer.getIdNumber())
                            .firstName(customer.getFirstName())
                            .lastName(customer.getLastName())
                            .dateOfBirth(customer.getDateOfBirth())
                            .build());
            applyKycResult(customer, result);
        } catch (Exception e) {
            log.warn("KYC verification failed for individual customer: {}", e.getMessage());
            customer.setKycStatus(KycStatus.FAILED);
            customer.setKycFailureReason("KYC provider unavailable: " + e.getMessage());
        }
    }

    private void runCorporateKyc(Customer customer, List<CustomerDirectorRequest> directorRequests) {
        customer.setKycStatus(KycStatus.IN_PROGRESS);
        try {
            KycResult corpResult = kycVerificationService.verifyCorporate(
                    CorporateKycRequest.builder()
                            .rcNumber(customer.getRcNumber())
                            .companyName(customer.getCompanyName())
                            .build());

            if (!corpResult.isVerified()) {
                customer.setKycStatus(KycStatus.FAILED);
                customer.setKycFailureReason(corpResult.getFailureReason());
                customer.setKycProviderRef(corpResult.getVerificationId());
                addDirectors(customer, directorRequests);
                return;
            }

            customer.setKycProviderRef(corpResult.getVerificationId());
            addDirectors(customer, directorRequests);
            verifyDirectors(customer);

            boolean allDirectorsPassed = customer.getDirectors().stream()
                    .allMatch(d -> d.getKycStatus() == KycStatus.PASSED);

            if (allDirectorsPassed) {
                customer.setKycStatus(KycStatus.PASSED);
                customer.setKycVerifiedAt(Instant.now());
            } else {
                customer.setKycStatus(KycStatus.FAILED);
                customer.setKycFailureReason("One or more directors failed KYC verification");
            }
        } catch (Exception e) {
            log.warn("Corporate KYC verification failed: {}", e.getMessage());
            customer.setKycStatus(KycStatus.FAILED);
            customer.setKycFailureReason("KYC provider unavailable: " + e.getMessage());
            addDirectors(customer, directorRequests);
        }
    }

    private void addDirectors(Customer customer, List<CustomerDirectorRequest> requests) {
        customer.getDirectors().clear();
        requests.forEach(r -> customer.getDirectors().add(
                CustomerDirector.builder()
                        .customer(customer)
                        .firstName(r.getFirstName())
                        .lastName(r.getLastName())
                        .dateOfBirth(r.getDateOfBirth())
                        .idType(r.getIdType())
                        .idNumber(r.getIdNumber())
                        .idExpiryDate(r.getIdExpiryDate())
                        .build()));
    }

    /** Validates that DL/Passport expiry date is present and not in the past. */
    private void validateExpiryDate(IdType idType, LocalDate expiryDate, String label) {
        if (idType == IdType.DRIVERS_LICENSE || idType == IdType.PASSPORT) {
            if (expiryDate == null) {
                throw new BusinessRuleException("MISSING_EXPIRY_DATE",
                        label + " expiry date is required for " + idType);
            }
            if (expiryDate.isBefore(LocalDate.now())) {
                throw new BusinessRuleException("EXPIRED_ID_DOCUMENT",
                        label + " has expired (expiry: " + expiryDate + "). Please provide a valid document.");
            }
        }
    }

    /** Uploads a KYC document file to MinIO and returns the stored path. */
    private String uploadKycDocument(MultipartFile file, UUID customerId, String docKey) {
        if (file == null || file.isEmpty()) return null;
        String tenantId = TenantContext.getTenantId() != null ? TenantContext.getTenantId() : "public";
        String ext = getExtension(file.getOriginalFilename());
        String path = "customers/" + customerId + "/kyc/" + docKey + ext;
        try {
            return documentStorageService.upload(tenantId, path, file.getInputStream(), file.getContentType());
        } catch (IOException e) {
            log.error("Failed to upload KYC document for customer {}: {}", customerId, e.getMessage());
            throw new BusinessRuleException("DOCUMENT_UPLOAD_FAILED", "Failed to upload " + docKey + ": " + e.getMessage());
        }
    }

    private String getExtension(String filename) {
        if (filename == null || !filename.contains(".")) return "";
        return filename.substring(filename.lastIndexOf('.'));
    }

    private void verifyDirectors(Customer customer) {
        customer.getDirectors().forEach(director -> {
            try {
                KycResult result = kycVerificationService.verifyDirector(
                        DirectorKycRequest.builder()
                                .idType(director.getIdType() != null ? director.getIdType().name() : null)
                                .idNumber(director.getIdNumber())
                                .firstName(director.getFirstName())
                                .lastName(director.getLastName())
                                .dateOfBirth(director.getDateOfBirth())
                                .build());
                director.setKycStatus(result.isVerified() ? KycStatus.PASSED : KycStatus.FAILED);
                director.setKycProviderRef(result.getVerificationId());
                director.setKycFailureReason(result.getFailureReason());
            } catch (Exception e) {
                director.setKycStatus(KycStatus.FAILED);
                director.setKycFailureReason("KYC provider unavailable: " + e.getMessage());
            }
        });
    }

    private void applyKycResult(Customer customer, KycResult result) {
        if (result.isVerified()) {
            customer.setKycStatus(KycStatus.PASSED);
            customer.setKycVerifiedAt(Instant.now());
        } else {
            customer.setKycStatus(KycStatus.FAILED);
            customer.setKycFailureReason(result.getFailureReason());
        }
        customer.setKycProviderRef(result.getVerificationId());
    }

    // ─── Helpers ────────────────────────────────────────────────────

    private void applyIndividualUpdate(Customer c, CustomerUpdateRequest r) {
        if (r.getFirstName() != null) c.setFirstName(r.getFirstName());
        if (r.getLastName() != null) c.setLastName(r.getLastName());
        if (r.getOtherNames() != null) c.setOtherNames(r.getOtherNames());
        if (r.getDateOfBirth() != null) c.setDateOfBirth(r.getDateOfBirth());
        if (r.getGender() != null) c.setGender(r.getGender());
        if (r.getMaritalStatus() != null) c.setMaritalStatus(r.getMaritalStatus());
        applyContactUpdate(c, r);
    }

    private void applyCorporateUpdate(Customer c, CustomerUpdateRequest r) {
        if (r.getCompanyName() != null) c.setCompanyName(r.getCompanyName());
        if (r.getIncorporationDate() != null) c.setIncorporationDate(r.getIncorporationDate());
        if (r.getIndustry() != null) c.setIndustry(r.getIndustry());
        if (r.getContactPerson() != null) c.setContactPerson(r.getContactPerson());
        applyContactUpdate(c, r);
    }

    private void applyContactUpdate(Customer c, CustomerUpdateRequest r) {
        if (r.getEmail() != null) c.setEmail(r.getEmail());
        if (r.getPhone() != null) c.setPhone(r.getPhone());
        if (r.getAlternatePhone() != null) c.setAlternatePhone(r.getAlternatePhone());
        if (r.getAddress() != null) c.setAddress(r.getAddress());
        if (r.getCity() != null) c.setCity(r.getCity());
        if (r.getState() != null) c.setState(r.getState());
        if (r.getCountry() != null) c.setCountry(r.getCountry());
    }

    public Customer findOrThrow(UUID id) {
        return repository.findById(id)
                .filter(c -> c.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("Customer", id));
    }

    private String currentUserId() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
            return jwt.getSubject();
        }
        return "system";
    }

    // ─── Mapping ────────────────────────────────────────────────────

    private CustomerSummaryResponse toSummary(Customer c) {
        String displayName = c.getCustomerType() == CustomerType.INDIVIDUAL
                ? c.getFirstName() + " " + c.getLastName()
                : c.getCompanyName();
        return CustomerSummaryResponse.builder()
                .id(c.getId()).customerNumber(c.getCustomerNumber())
                .customerType(c.getCustomerType())
                .customerStatus(c.getCustomerStatus()).kycStatus(c.getKycStatus())
                .displayName(displayName).email(c.getEmail()).phone(c.getPhone())
                .createdAt(c.getCreatedAt())
                .build();
    }

    CustomerResponse toResponse(Customer c) {
        List<CustomerDirectorResponse> directors = c.getDirectors() == null ? List.of() :
                c.getDirectors().stream()
                        .filter(d -> d.getDeletedAt() == null)
                        .map(d -> CustomerDirectorResponse.builder()
                                .id(d.getId()).firstName(d.getFirstName()).lastName(d.getLastName())
                                .dateOfBirth(d.getDateOfBirth()).idType(d.getIdType())
                                .idNumber(d.getIdNumber()).idDocumentUrl(d.getIdDocumentUrl())
                                .idExpiryDate(d.getIdExpiryDate()).kycStatus(d.getKycStatus())
                                .kycFailureReason(d.getKycFailureReason())
                                .build())
                        .toList();

        List<CustomerDocumentResponse> docs = c.getDocuments() == null ? List.of() :
                c.getDocuments().stream()
                        .filter(d -> d.getDeletedAt() == null)
                        .map(d -> CustomerDocumentResponse.builder()
                                .id(d.getId()).documentType(d.getDocumentType())
                                .documentName(d.getDocumentName()).documentPath(d.getDocumentPath())
                                .mimeType(d.getMimeType()).fileSizeBytes(d.getFileSizeBytes())
                                .uploadedBy(d.getUploadedBy()).createdAt(d.getCreatedAt())
                                .build())
                        .toList();

        return CustomerResponse.builder()
                .id(c.getId()).customerNumber(c.getCustomerNumber())
                .customerType(c.getCustomerType())
                .customerStatus(c.getCustomerStatus()).kycStatus(c.getKycStatus())
                .kycProviderRef(c.getKycProviderRef()).kycFailureReason(c.getKycFailureReason())
                .kycVerifiedAt(c.getKycVerifiedAt())
                .firstName(c.getFirstName()).lastName(c.getLastName()).otherNames(c.getOtherNames())
                .dateOfBirth(c.getDateOfBirth()).gender(c.getGender()).maritalStatus(c.getMaritalStatus())
                .idType(c.getIdType()).idNumber(c.getIdNumber())
                .idDocumentUrl(c.getIdDocumentUrl()).idExpiryDate(c.getIdExpiryDate())
                .companyName(c.getCompanyName()).rcNumber(c.getRcNumber())
                .cacCertificateUrl(c.getCacCertificateUrl()).cacIssuedDate(c.getCacIssuedDate())
                .incorporationDate(c.getIncorporationDate()).industry(c.getIndustry())
                .contactPerson(c.getContactPerson())
                .email(c.getEmail()).phone(c.getPhone()).alternatePhone(c.getAlternatePhone())
                .address(c.getAddress()).city(c.getCity()).state(c.getState()).country(c.getCountry())
                .directors(directors).documents(docs)
                .createdAt(c.getCreatedAt()).updatedAt(c.getUpdatedAt())
                .build();
    }
}
