package com.nubeero.cia.reports.domain;

import com.nubeero.cia.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "report_definition",
       indexes = {
           @Index(name = "idx_report_def_category", columnList = "category"),
           @Index(name = "idx_report_def_type",     columnList = "type")
       })
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReportDefinition extends BaseEntity {

    @Column(nullable = false, length = 200)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private ReportCategory category;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReportType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "data_source", nullable = false, length = 50)
    private DataSource dataSource;

    @Convert(converter = ReportConfigConverter.class)
    @Column(nullable = false, columnDefinition = "jsonb")
    private ReportConfig config;

    @Column(name = "is_pinnable", nullable = false)
    @Builder.Default
    private boolean pinnable = true;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private boolean active = true;
}
