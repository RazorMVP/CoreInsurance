package com.nubeero.cia.policy;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.nubeero.cia.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "policy_risks")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PolicyRisk extends BaseEntity {

    // @JsonIgnore breaks the Policy↔PolicyRisk bidirectional cycle so the
    // AuditService snapshot (objectMapper.writeValueAsString(policy)) does not
    // recurse Policy → risks → risk → policy → … infinitely. Without it,
    // serialisation throws, falls back to toString(), and the audit_log JSONB
    // INSERT fails — rolling back the whole create()/bindFromQuote() txn.
    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "policy_id", nullable = false)
    private Policy policy;

    @Column(nullable = false, length = 500)
    private String description;

    @Column(name = "sum_insured", nullable = false, precision = 18, scale = 2)
    private BigDecimal sumInsured;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal premium;

    @Column(name = "section_id")
    private UUID sectionId;

    @Column(name = "section_name", length = 100)
    private String sectionName;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "risk_details", columnDefinition = "jsonb")
    private Map<String, Object> riskDetails;

    @Column(name = "vehicle_reg_number", length = 20)
    private String vehicleRegNumber;

    @Column(name = "order_no", nullable = false)
    private int orderNo;
}
