package com.nubeero.cia.setup.policy;

import com.nubeero.cia.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Policy clause master — the clause bank surfaced in Setup → Policy Specifications. Quotes and
 * policies snapshot the selected clauses ({@code title}/{@code text}) at selection time, so this
 * is reference master data, not the rendered source of an issued document.
 */
@Entity
@Table(name = "clauses")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Clause extends BaseEntity {

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String text;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ClauseType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ClauseApplicability applicability;

    /** Product UUIDs this clause applies to; empty = applies to all products. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "product_ids", columnDefinition = "jsonb")
    @Builder.Default
    private List<String> productIds = new ArrayList<>();
}
