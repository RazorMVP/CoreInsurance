package com.nubeero.cia.customer;

import com.nubeero.cia.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "customer_documents")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CustomerDocument extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    @Column(name = "document_type", nullable = false, length = 50)
    private String documentType;

    @Column(name = "document_name", nullable = false, length = 200)
    private String documentName;

    @Column(name = "document_path", nullable = false, columnDefinition = "TEXT")
    private String documentPath;

    @Column(name = "mime_type", length = 100)
    private String mimeType;

    @Column(name = "file_size_bytes")
    private Long fileSizeBytes;

    @Column(name = "uploaded_by", length = 100)
    private String uploadedBy;
}
