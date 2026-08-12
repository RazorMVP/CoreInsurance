package com.nubeero.cia.finance.paa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PaaLrcRepository extends JpaRepository<PaaLrc, UUID> {

    Optional<PaaLrc> findByGroupIdAndPeriodIdAndDeletedAtIsNull(UUID groupId, UUID periodId);

    List<PaaLrc> findByGroupIdAndDeletedAtIsNullOrderByPeriodIdAsc(UUID groupId);

    List<PaaLrc> findByPeriodIdAndDeletedAtIsNullOrderByGroupIdAsc(UUID periodId);

    boolean existsByPeriodIdAndDeletedAtIsNull(UUID periodId);

    /**
     * The group's most recently periodised roll-forward row, by the
     * enclosing period's end date (NOT {@code period_id} — that's a random
     * UUID, not chronological). {@code FacDerecognitionListener} (Task 5)
     * reads this row's {@code closingBalance} as "the remaining
     * unearned/unamortised balance" for a cancelled contract's group — the
     * brief's v1 scope assumes a single-contract group, so the group's
     * closing balance IS that contract's remaining balance.
     */
    Optional<PaaLrc> findFirstByGroupIdAndDeletedAtIsNullOrderByPeriodEndDateDesc(UUID groupId);
}
