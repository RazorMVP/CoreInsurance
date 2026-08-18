package com.nubeero.cia.finance.paa;

import com.nubeero.cia.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * IFRS 17 §22 permanent assignment of a contract to a group of contracts at
 * initial recognition. Written by {@link ContractGroupingService} on
 * {@code PolicyApprovedEvent} for direct policies ({@link ContractType#POLICY}).
 * {@link ContractType#FAC_INWARD} / {@link ContractType#FAC_OUTWARD} are
 * reserved for facultative reinsurance contracts, wired by a later slice of
 * the FAC / IFRS-17 PAA workstream.
 *
 * <p>Generalised (workstream Task 1) from the policy-only
 * {@code PolicyGroupAssignment} — {@code (contractType, contractId)}
 * replaces the single {@code policyId} column so the same table can carry
 * direct policies and (later) FAC contracts. {@code contractId} is a raw
 * UUID rather than a {@code @ManyToOne} — the PAA module doesn't pull the
 * cia-policy {@code Policy} mapping (or a future cia-reinsurance FAC
 * mapping) into its persistence context, and a single-target FK across two
 * possible source tables isn't representable at the DB level anyway. The
 * V77 migration enforces referential integrity only on {@code group_id}.
 *
 * <p>UNIQUE on {@code (contract_type, contract_id)} means a duplicate-event
 * re-fire surfaces as a constraint violation rather than a duplicate
 * assignment. The service checks
 * {@link ContractGroupAssignmentRepository#findByContractTypeAndContractIdAndDeletedAtIsNull}
 * before insert as the fast path.
 */
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "contract_group_assignment")
public class ContractGroupAssignment extends BaseEntity {

    @Enumerated(EnumType.STRING)
    @Column(name = "contract_type", nullable = false, updatable = false, length = 20)
    private ContractType contractType;

    @Column(name = "contract_id", nullable = false, updatable = false)
    private UUID contractId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "group_id", nullable = false, updatable = false)
    private GroupOfContracts group;

    @Column(name = "assigned_at", nullable = false, updatable = false)
    private Instant assignedAt;
}
