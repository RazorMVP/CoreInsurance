package com.nubeero.cia.reports.domain;

import com.nubeero.cia.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

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

    /**
     * Stored as PostgreSQL JSONB. Hibernate 6 native JSON binding via
     * {@code @JdbcTypeCode(SqlTypes.JSON)} — uses Jackson for serialization
     * (auto-discovered from the classpath) and binds the column with the
     * correct {@code jsonb} type code, so PostgreSQL accepts the parameter
     * on INSERT without a JDBC URL-level {@code stringtype=unspecified} hack.
     *
     * Replaces the older {@code @Convert(ReportConfigConverter.class)}
     * pattern which wrote the value as VARCHAR and got rejected by the
     * jsonb column on INSERT.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private ReportConfig config;

    @Column(name = "is_pinnable", nullable = false)
    @Builder.Default
    private boolean pinnable = true;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private boolean active = true;
}
