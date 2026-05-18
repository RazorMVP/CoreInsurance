package com.nubeero.cia.finance.paa;

import com.nubeero.cia.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * IFRS 17 §22 permanent assignment of a policy to a group of contracts at
 * initial recognition. Written by {@link ContractGroupingService} on
 * {@code PolicyApprovedEvent}.
 *
 * <p>{@code policyId} is a raw UUID rather than a {@code @ManyToOne} to
 * {@code Policy} so the PAA module doesn't pull the cia-policy {@code Policy}
 * mapping into its persistence context — same loose-coupling pattern as the
 * other PAA entities. The DB-level FK in V37 still enforces referential
 * integrity.
 *
 * <p>UNIQUE on {@code policy_id} means a duplicate-event re-fire surfaces as
 * a constraint violation rather than a duplicate assignment. The service
 * checks {@link PolicyGroupAssignmentRepository#existsByPolicyIdAndDeletedAtIsNull}
 * before insert as the fast path.
 */
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "policy_group_assignment")
public class PolicyGroupAssignment extends BaseEntity {

    @Column(name = "policy_id", nullable = false, updatable = false, unique = true)
    private UUID policyId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "group_id", nullable = false, updatable = false)
    private GroupOfContracts group;

    @Column(name = "assigned_at", nullable = false, updatable = false)
    private Instant assignedAt;
}
