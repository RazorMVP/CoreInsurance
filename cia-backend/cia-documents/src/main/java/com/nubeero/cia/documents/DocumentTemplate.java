package com.nubeero.cia.documents;

import com.nubeero.cia.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

@Entity
@Table(name = "document_templates")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentTemplate extends BaseEntity {

    @Enumerated(EnumType.STRING)
    @Column(name = "template_type", nullable = false, length = 40)
    private DocumentTemplateType templateType;

    // null = applies to all products
    @Column(name = "product_id")
    private UUID productId;

    // null = applies to all classes
    @Column(name = "class_of_business_id")
    private UUID classOfBusinessId;

    // Path in MinIO where the HTML template is stored
    @Column(name = "storage_path", nullable = false, length = 500)
    private String storagePath;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;
}
