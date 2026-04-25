package com.nubeero.cia.reports.domain;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "report_access_policy",
       indexes = @Index(name = "idx_report_access_group", columnList = "access_group_id"))
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReportAccessPolicy {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "access_group_id", nullable = false)
    private UUID accessGroupId;

    /** NULL = report-level only */
    @Enumerated(EnumType.STRING)
    @Column(length = 50)
    private ReportCategory category;

    /** NULL = category-level only */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "report_id")
    private ReportDefinition report;

    @Column(name = "can_view", nullable = false)
    @Builder.Default
    private boolean canView = false;

    @Column(name = "can_export_csv", nullable = false)
    @Builder.Default
    private boolean canExportCsv = false;

    @Column(name = "can_export_pdf", nullable = false)
    @Builder.Default
    private boolean canExportPdf = false;

    @CreatedDate
    @Column(name = "created_at", updatable = false, nullable = false)
    private Instant createdAt;
}
