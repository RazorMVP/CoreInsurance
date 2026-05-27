package com.nubeero.cia.finance.audit;

import com.nubeero.cia.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Server-side PDF download audit row. Written by
 * {@link com.nubeero.cia.finance.ReceiptController#downloadPdf} and
 * {@link com.nubeero.cia.finance.PaymentController#downloadPdf} after a
 * successful storage download.
 *
 * @since F11
 */
@Entity
@Table(name = "pdf_download_log")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PdfDownloadLog extends BaseEntity {

    @Column(name = "user_id", nullable = false, length = 100)
    private String userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "entity_type", nullable = false, length = 20)
    private PdfDocumentType entityType;

    @Column(name = "entity_id", nullable = false)
    private UUID entityId;

    @Column(name = "reference", nullable = false, length = 60)
    private String reference;

    @Column(name = "parent_id")
    private UUID parentId;

    @Column(name = "parent_ref", length = 60)
    private String parentRef;

    @Column(name = "recipient_name", length = 200)
    private String recipientName;

    @Column(name = "downloaded_at", nullable = false)
    private Instant downloadedAt;
}
