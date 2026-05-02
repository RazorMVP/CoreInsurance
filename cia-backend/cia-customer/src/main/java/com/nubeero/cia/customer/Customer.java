package com.nubeero.cia.customer;

import com.nubeero.cia.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.ColumnTransformer;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "customers")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Customer extends BaseEntity {

    @Column(name = "customer_number", length = 60, unique = true)
    private String customerNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "customer_type", nullable = false, length = 20)
    private CustomerType customerType;

    @Enumerated(EnumType.STRING)
    @Column(name = "customer_status", nullable = false, length = 20)
    @Builder.Default
    private CustomerStatus customerStatus = CustomerStatus.ACTIVE;

    @Enumerated(EnumType.STRING)
    @Column(name = "kyc_status", nullable = false, length = 20)
    @Builder.Default
    private KycStatus kycStatus = KycStatus.PENDING;

    @Column(name = "kyc_provider_ref", length = 100)
    private String kycProviderRef;

    @Column(name = "kyc_failure_reason", columnDefinition = "TEXT")
    private String kycFailureReason;

    @Column(name = "kyc_verified_at")
    private Instant kycVerifiedAt;

    // Individual fields
    @Column(name = "first_name", length = 100)
    private String firstName;

    @Column(name = "last_name", length = 100)
    private String lastName;

    @Column(name = "other_names", length = 100)
    private String otherNames;

    @Column(name = "date_of_birth")
    private LocalDate dateOfBirth;

    @Column(length = 10)
    private String gender;

    @Column(name = "marital_status", length = 20)
    private String maritalStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "id_type", length = 30)
    private IdType idType;

    // NDPR: encrypted at rest via pgcrypto. See V24 migration + application.yml
    // (cia.security.pii-key + spring.datasource.hikari.connection-init-sql).
    @ColumnTransformer(
        read  = "pgp_sym_decrypt(id_number, current_setting('app.pii_key'))",
        write = "pgp_sym_encrypt(?, current_setting('app.pii_key'))"
    )
    @Column(name = "id_number", columnDefinition = "bytea")
    private String idNumber;

    @ColumnTransformer(
        read  = "pgp_sym_decrypt(id_document_url, current_setting('app.pii_key'))",
        write = "pgp_sym_encrypt(?, current_setting('app.pii_key'))"
    )
    @Column(name = "id_document_url", columnDefinition = "bytea")
    private String idDocumentUrl;

    @Column(name = "id_expiry_date")
    private LocalDate idExpiryDate;

    // Corporate fields
    @Column(name = "company_name", length = 200)
    private String companyName;

    @Column(name = "rc_number", length = 50)
    private String rcNumber;

    @Column(name = "cac_certificate_url", length = 500)
    private String cacCertificateUrl;

    @Column(name = "cac_issued_date")
    private LocalDate cacIssuedDate;

    @Column(name = "incorporation_date")
    private LocalDate incorporationDate;

    @Column(length = 100)
    private String industry;

    @Column(name = "contact_person", length = 200)
    private String contactPerson;

    // Common contact & address
    @Column(length = 200)
    private String email;

    @Column(length = 30)
    private String phone;

    @Column(name = "alternate_phone", length = 30)
    private String alternatePhone;

    // NDPR: encrypted at rest via pgcrypto.
    @ColumnTransformer(
        read  = "pgp_sym_decrypt(address, current_setting('app.pii_key'))",
        write = "pgp_sym_encrypt(?, current_setting('app.pii_key'))"
    )
    @Column(columnDefinition = "bytea")
    private String address;

    @Column(length = 100)
    private String city;

    @Column(length = 100)
    private String state;

    @Column(length = 100, nullable = false)
    @Builder.Default
    private String country = "Nigeria";

    // Blacklist
    @Column(name = "blacklist_reason", columnDefinition = "TEXT")
    private String blacklistReason;

    @Column(name = "blacklisted_at")
    private Instant blacklistedAt;

    @Column(name = "blacklisted_by", length = 100)
    private String blacklistedBy;

    @OneToMany(mappedBy = "customer", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<CustomerDirector> directors = new ArrayList<>();

    @OneToMany(mappedBy = "customer", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<CustomerDocument> documents = new ArrayList<>();
}
