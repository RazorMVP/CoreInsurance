package com.nubeero.cia.finance.paa;

import com.nubeero.cia.finance.gl.FiscalPeriod;
import com.nubeero.cia.finance.gl.FiscalPeriodNotFoundException;
import com.nubeero.cia.finance.gl.FiscalPeriodRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Orchestrates the PAA period-close batch — runs {@link LrcEngine} and
 * {@link LicEngine} for one fiscal period and returns the combined result
 * alongside the §83 / §84 Insurance Service Result. Module 12 Phase 2
 * Slice 2.5.
 *
 * <h2>Idempotency strategy</h2>
 * <p>The orchestrator pre-checks whether each engine has already written
 * for the period (any paa_lrc / paa_lic row exists) and skips that engine
 * if so. This makes {@link #closePeriod} naturally idempotent — re-running
 * after partial completion picks up only the missing engine, and a re-run
 * of a fully-closed period is a no-op that still returns the current
 * insurance service result.
 *
 * <p>The downstream engines retain their per-group {@code AlreadyDone}
 * exception semantics — they would still throw if individual groups had
 * been recognised but others hadn't (e.g. mid-engine crash). The
 * orchestrator's pre-check is intentionally coarse (any row for the
 * period) so it doesn't paper over genuine partial-write situations; if
 * the engines crashed mid-way the operator must reverse them before
 * re-running.
 *
 * <h2>Why pre-check rather than catch?</h2>
 * <p>Catching {@link LrcRecognitionAlreadyDoneException} mid-loop would
 * leave the engine partially committed for groups before the conflict,
 * which then surfaces inconsistently on the next re-run. The pre-check
 * lets us decide before any DB write begins, keeping orchestration atomic.
 */
@Service
@Slf4j
@Transactional
@RequiredArgsConstructor
public class PaaPeriodCloseService {

    private final FiscalPeriodRepository fiscalPeriodRepository;
    private final LrcEngine lrcEngine;
    private final LicEngine licEngine;
    private final PaaLrcRepository lrcRepository;
    private final PaaLicRepository licRepository;
    private final InsuranceServiceResultService insuranceServiceResultService;
    private final EntityManager entityManager;

    public PaaPeriodCloseResult closePeriod(UUID periodId) {
        FiscalPeriod period = fiscalPeriodRepository.findById(periodId)
            .filter(p -> p.getDeletedAt() == null)
            .orElseThrow(() -> new FiscalPeriodNotFoundException(periodId));

        log.info("PAA period close starting for {} ({} → {})",
            periodId, period.getStartDate(), period.getEndDate());

        LrcRecognitionResult lrcResult;
        if (lrcRepository.existsByPeriodIdAndDeletedAtIsNull(periodId)) {
            log.info("Skipping LrcEngine for period {} — paa_lrc rows already exist", periodId);
            lrcResult = null;
        } else {
            lrcResult = lrcEngine.recognise(periodId);
        }

        LicRecognitionResult licResult;
        if (licRepository.existsByPeriodIdAndDeletedAtIsNull(periodId)) {
            log.info("Skipping LicEngine for period {} — paa_lic rows already exist", periodId);
            licResult = null;
        } else {
            licResult = licEngine.recognise(periodId);
        }

        // Force-push engine writes to PG before the InsuranceServiceResultService
        // reads paa_lrc + paa_lic via JdbcTemplate. Within a single @Transactional
        // boundary Hibernate may keep the inserts buffered in its persistence
        // context until commit — JdbcTemplate uses the same JDBC connection so
        // a flush makes them visible without committing.
        entityManager.flush();

        InsuranceServiceResult serviceResult = insuranceServiceResultService.compute(periodId);

        log.info("PAA period close complete for {} — revenue {}, expense {}, result {}",
            periodId,
            serviceResult.totalInsuranceRevenue(),
            serviceResult.totalInsuranceServiceExpense(),
            serviceResult.totalInsuranceServiceResult());

        return new PaaPeriodCloseResult(
            period.getId(),
            period.getStartDate(),
            period.getEndDate(),
            lrcResult,
            licResult,
            serviceResult);
    }
}
