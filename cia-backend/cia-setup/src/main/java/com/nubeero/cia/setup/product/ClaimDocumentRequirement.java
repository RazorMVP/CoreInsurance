package com.nubeero.cia.setup.product;

import com.nubeero.cia.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "claim_document_requirements")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClaimDocumentRequirement extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(name = "document_name", nullable = false)
    private String documentName;

    @Column(name = "is_mandatory", nullable = false)
    private boolean isMandatory;

    /**
     * Optional link to the {@code ClaimDocumentType} enum so a per-claim
     * checklist can match this requirement to an uploaded document.
     * Stored as the enum name (e.g. {@code POLICE_REPORT}); NULL means
     * the requirement is informational only and won't be auto-matched.
     */
    @Column(name = "document_type", length = 50)
    private String documentType;
}
