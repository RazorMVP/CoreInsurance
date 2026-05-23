package com.nubeero.cia.setup.org;

import com.nubeero.cia.common.audit.AuditService;
import com.nubeero.cia.common.exception.ResourceInUseException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit-level coverage for {@link BranchService#delete(UUID, String)}'s
 * FK-cascade-awareness against RelationshipManager. Backlog row {@code E3}.
 *
 * <p>Mockito-only; mirrors {@code CommissionSetupRequestValidationTest}'s
 * "fast unit test alongside the production module" pattern. A full
 * Testcontainers IT would prove the same property at much higher cost —
 * the boundary being tested is purely application-layer (PostgreSQL's FK
 * constraint is oblivious to {@code deleted_at IS NULL}, so the check
 * lives in service code).
 */
@ExtendWith(MockitoExtension.class)
class BranchServiceDeleteTest {

    @Mock private BranchRepository branchRepository;
    @Mock private SbuRepository sbuRepository;
    @Mock private RelationshipManagerRepository relationshipManagerRepository;
    @Mock private AuditService auditService;

    @InjectMocks private BranchService service;

    private final UUID branchId = UUID.randomUUID();

    private Branch existingBranch() {
        Branch b = Branch.builder().name("Lagos Branch").code("LAG").build();
        b.setId(branchId);
        return b;
    }

    @Test
    @DisplayName("delete — soft-deletes when no active RM references the branch")
    void delete_whenNoActiveRms_softDeletes() {
        Branch branch = existingBranch();
        when(branchRepository.findById(branchId)).thenReturn(Optional.of(branch));
        when(relationshipManagerRepository.countByBranchIdAndDeletedAtIsNull(branchId)).thenReturn(0L);

        service.delete(branchId, "no longer operational");

        // The Branch entity was soft-deleted (deleted_at populated) and saved.
        assertThat(branch.getDeletedAt()).isNotNull();
        verify(branchRepository, times(1)).save(branch);
        verify(auditService, times(1))
                .logWithReason(eq("Branch"), eq(branchId.toString()), any(), eq(branch), eq(null), eq("no longer operational"));
    }

    @Test
    @DisplayName("delete — throws ResourceInUseException (409 CONFLICT) when active RMs reference the branch")
    void delete_whenActiveRmsExist_throwsResourceInUse() {
        Branch branch = existingBranch();
        when(branchRepository.findById(branchId)).thenReturn(Optional.of(branch));
        when(relationshipManagerRepository.countByBranchIdAndDeletedAtIsNull(branchId)).thenReturn(3L);

        assertThatThrownBy(() -> service.delete(branchId, "tearing down"))
                .isInstanceOfSatisfying(ResourceInUseException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo("RESOURCE_IN_USE");
                    assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(ex.getMessage())
                            .contains("Branch")
                            .contains(branchId.toString())
                            .contains("3")
                            .contains("RelationshipManager");
                });

        // The branch entity was NOT soft-deleted, not saved, and no audit
        // log was written — the check fires before any state mutation.
        assertThat(branch.getDeletedAt()).isNull();
        verify(branchRepository, never()).save(any());
        verify(auditService, never())
                .logWithReason(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("delete — soft-deletes when exactly one soft-deleted RM previously referenced the branch (boundary)")
    void delete_whenOnlySoftDeletedRmsExist_softDeletes() {
        // countByBranchIdAndDeletedAtIsNull excludes soft-deleted RMs by
        // its name. This test pins that contract: a branch whose only RM
        // refs are themselves soft-deleted is freely deletable.
        Branch branch = existingBranch();
        when(branchRepository.findById(branchId)).thenReturn(Optional.of(branch));
        when(relationshipManagerRepository.countByBranchIdAndDeletedAtIsNull(branchId)).thenReturn(0L);

        service.delete(branchId, "consolidation");

        assertThat(branch.getDeletedAt()).isNotNull();
        verify(branchRepository, times(1)).save(branch);
    }
}
