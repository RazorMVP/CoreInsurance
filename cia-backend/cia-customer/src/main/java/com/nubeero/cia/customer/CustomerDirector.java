package com.nubeero.cia.customer;

import com.nubeero.cia.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.ColumnTransformer;

import java.time.LocalDate;

@Entity
@Table(name = "customer_directors")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CustomerDirector extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    @Column(name = "first_name", nullable = false, length = 100)
    private String firstName;

    @Column(name = "last_name", nullable = false, length = 100)
    private String lastName;

    @Column(name = "date_of_birth")
    private LocalDate dateOfBirth;

    @Enumerated(EnumType.STRING)
    @Column(name = "id_type", length = 30)
    private IdType idType;

    // NDPR: encrypted at rest via pgcrypto. See V24 migration + application.yml.
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

    @Enumerated(EnumType.STRING)
    @Column(name = "kyc_status", nullable = false, length = 20)
    @Builder.Default
    private KycStatus kycStatus = KycStatus.PENDING;

    @Column(name = "kyc_provider_ref", length = 100)
    private String kycProviderRef;

    @Column(name = "kyc_failure_reason", columnDefinition = "TEXT")
    private String kycFailureReason;
}
