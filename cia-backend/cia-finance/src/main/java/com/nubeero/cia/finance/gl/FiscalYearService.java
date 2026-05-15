package com.nubeero.cia.finance.gl;

import com.nubeero.cia.finance.dto.CreateFiscalYearRequest;
import com.nubeero.cia.finance.dto.FiscalPeriodResponse;
import com.nubeero.cia.finance.dto.FiscalYearResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Tenant-configurable fiscal year lifecycle. Owns the FY rows and the
 * generation of their child {@link FiscalPeriod} rows.
 *
 * <p>Slice 1.6 (Module 12 — Period-End Closures). The four decisions
 * locked at design time:
 *
 * <ul>
 *   <li><b>D1=A</b> — caller may omit {@code startDate} / {@code endDate}
 *       on {@link #create(CreateFiscalYearRequest)}; defaults to current
 *       calendar year (Jan 1 → Dec 31).</li>
 *   <li><b>D2=A</b> — child periods (12 MONTH + 4 QUARTER + 2 HALF_YEAR +
 *       1 YEAR = 19 rows) are generated at create time. DAY periods are
 *       lazy via {@link FiscalPeriodResolver} (d10).</li>
 *   <li><b>D3=B</b> — {@link #activate(UUID)} refuses if any other FY is
 *       already {@link FiscalYearStatus#ACTIVE}; admin must
 *       {@link #close(UUID)} the prior FY explicitly. Trades the V31
 *       comment's "atomic sibling deactivation" for clearer
 *       {@code CLOSED}-means-finished audit semantics.</li>
 *   <li><b>D4=A</b> — {@link #bootstrapForNewTenant()} is idempotent:
 *       returns the existing {@code ACTIVE} FY if one already exists,
 *       otherwise creates + activates a calendar-year FY.</li>
 * </ul>
 *
 * <p>Quarter and half-year boundaries are <b>FY-relative</b> (d8): for a
 * calendar FY, Q1 = Jan-Mar. For an April-March FY, Q1 = Apr-Jun. This
 * matches conventional accounting and avoids a misalignment between
 * management reports and board-approved budgets when reporting "Q1
 * results".
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FiscalYearService {

    private final FiscalYearRepository fiscalYearRepository;
    private final FiscalPeriodRepository fiscalPeriodRepository;
    private final JournalEntryRepository journalEntryRepository;
    private final Clock clock;

    // ── reads ────────────────────────────────────────────────────────────────

    public List<FiscalYearResponse> listAll() {
        return fiscalYearRepository.findAllByDeletedAtIsNullOrderByStartDateDesc().stream()
            .map(fy -> toResponse(fy, null))
            .toList();
    }

    public FiscalYearResponse get(UUID id, boolean includePeriods) {
        FiscalYear fy = loadOrThrow(id);
        List<FiscalPeriodResponse> periods = includePeriods ? listPeriodResponses(fy) : null;
        return toResponse(fy, periods);
    }

    public List<FiscalPeriodResponse> listPeriods(UUID fiscalYearId) {
        FiscalYear fy = loadOrThrow(fiscalYearId);
        return listPeriodResponses(fy);
    }

    public FiscalYearResponse findActive() {
        FiscalYear fy = findActiveOrNull()
            .orElseThrow(FiscalYearNotFoundException::noActiveYear);
        return toResponse(fy, null);
    }

    /**
     * Returns the FY whose date range encloses {@code date}, or empty if
     * none exists. Used by {@link FiscalPeriodResolver} for lazy DAY-period
     * generation (d10).
     */
    public Optional<FiscalYear> findEnclosingEntity(LocalDate date) {
        return fiscalYearRepository.findEnclosing(date);
    }

    // ── mutations ────────────────────────────────────────────────────────────

    /**
     * Creates a fiscal year and immediately generates its 19 bounded child
     * periods (12 MONTH + 4 QUARTER + 2 HALF_YEAR + 1 YEAR). The new FY
     * starts in {@link FiscalYearStatus#PLANNING} status — activate it
     * separately via {@link #activate(UUID)} (D3=B).
     *
     * <p>All request fields are optional (D1=A); omitted dates default to
     * the current calendar year and an omitted name defaults to
     * {@code "FY" + startDate.getYear()} (d9).
     */
    @Transactional
    public FiscalYearResponse create(CreateFiscalYearRequest request) {
        LocalDate startDate = request.startDate() != null
            ? request.startDate()
            : LocalDate.of(LocalDate.now(clock).getYear(), 1, 1);
        LocalDate endDate = request.endDate() != null
            ? request.endDate()
            : startDate.plusYears(1).minusDays(1);

        validateBounds(startDate, endDate);

        String name = (request.name() != null && !request.name().isBlank())
            ? request.name().trim()
            : "FY" + startDate.getYear();

        fiscalYearRepository.findByNameAndDeletedAtIsNull(name).ifPresent(existing -> {
            throw new FiscalYearNameConflictException(name);
        });

        FiscalYear fy = new FiscalYear();
        fy.setName(name);
        fy.setStartDate(startDate);
        fy.setEndDate(endDate);
        fy.setStatus(FiscalYearStatus.PLANNING);
        fy.setCreatedBy(currentUser());
        FiscalYear saved = fiscalYearRepository.save(fy);

        List<FiscalPeriod> periods = buildBoundedPeriods(saved);
        fiscalPeriodRepository.saveAll(periods);

        return toResponse(saved, periods.stream().map(this::toPeriodResponse).toList());
    }

    /**
     * Flips a FY from {@code PLANNING} to {@code ACTIVE}. D3=B: refuses if
     * any other FY is already {@code ACTIVE} (must be closed first).
     */
    @Transactional
    public FiscalYearResponse activate(UUID id) {
        FiscalYear fy = loadOrThrow(id);
        if (fy.getStatus() == FiscalYearStatus.ACTIVE) {
            return toResponse(fy, null); // idempotent
        }
        if (fy.getStatus() == FiscalYearStatus.CLOSED) {
            throw new com.nubeero.cia.common.exception.BusinessRuleException(
                "FISCAL_YEAR_ALREADY_CLOSED",
                "Cannot activate fiscal year " + id + ": status is CLOSED.");
        }
        // PLANNING → check for sibling ACTIVE (D3=B)
        findActiveOrNull().ifPresent(active -> {
            throw new FiscalYearActivationConflictException(active.getId(), active.getName());
        });

        fy.setStatus(FiscalYearStatus.ACTIVE);
        return toResponse(fiscalYearRepository.save(fy), null);
    }

    /**
     * Flips an {@code ACTIVE} FY to {@code CLOSED}. Period-level postings
     * are governed independently by Slice 1.7's period_lock; closing the
     * FY just retires the "current year" badge so a successor can be
     * activated.
     */
    @Transactional
    public FiscalYearResponse close(UUID id) {
        FiscalYear fy = loadOrThrow(id);
        if (fy.getStatus() == FiscalYearStatus.CLOSED) {
            return toResponse(fy, null); // idempotent
        }
        if (fy.getStatus() != FiscalYearStatus.ACTIVE) {
            throw new com.nubeero.cia.common.exception.BusinessRuleException(
                "FISCAL_YEAR_NOT_ACTIVE",
                "Cannot close fiscal year " + id + ": only ACTIVE fiscal years can be closed " +
                    "(current status: " + fy.getStatus() + ").");
        }
        fy.setStatus(FiscalYearStatus.CLOSED);
        return toResponse(fiscalYearRepository.save(fy), null);
    }

    /**
     * Soft-deletes a fiscal year — rejected if any journal entries
     * reference its child periods (d11).
     */
    @Transactional
    public void delete(UUID id) {
        FiscalYear fy = loadOrThrow(id);
        List<UUID> periodIds = fiscalPeriodRepository.findIdsByFiscalYearId(fy.getId());
        if (!periodIds.isEmpty()) {
            long jeCount = journalEntryRepository.countByPeriodIdInAndDeletedAtIsNull(periodIds);
            if (jeCount > 0L) {
                throw new FiscalYearHasJournalEntriesException(fy.getId(), jeCount);
            }
        }
        fy.softDelete();
        fiscalYearRepository.save(fy);
    }

    /**
     * Idempotent tenant bootstrap (D4=A). If any {@code ACTIVE} FY already
     * exists, returns it. Otherwise creates a current-calendar-year FY,
     * activates it, and returns the activated row. Intended to be called
     * by the tenant-provisioning workflow on tenant create.
     */
    @Transactional
    public FiscalYearResponse bootstrapForNewTenant() {
        Optional<FiscalYear> existing = findActiveOrNull();
        if (existing.isPresent()) {
            return toResponse(existing.get(), null);
        }
        FiscalYearResponse created = create(new CreateFiscalYearRequest(null, null, null));
        return activate(created.id());
    }

    // ── period generation (FY-relative quarters / halves, d8) ────────────────

    private List<FiscalPeriod> buildBoundedPeriods(FiscalYear fy) {
        LocalDate start = fy.getStartDate();
        List<FiscalPeriod> periods = new ArrayList<>(19);

        // 12 MONTH periods aligned to FY start month.
        for (int i = 0; i < 12; i++) {
            LocalDate periodStart = start.plusMonths(i);
            LocalDate periodEnd = periodStart.with(TemporalAdjusters.lastDayOfMonth());
            periods.add(buildPeriod(fy, FiscalPeriodType.MONTH, periodStart, periodEnd));
        }

        // 4 QUARTER periods (3 months each, advancing from FY start).
        for (int i = 0; i < 4; i++) {
            LocalDate periodStart = start.plusMonths(i * 3L);
            LocalDate periodEnd = periodStart.plusMonths(3).minusDays(1);
            periods.add(buildPeriod(fy, FiscalPeriodType.QUARTER, periodStart, periodEnd));
        }

        // 2 HALF_YEAR periods (6 months each, advancing from FY start).
        for (int i = 0; i < 2; i++) {
            LocalDate periodStart = start.plusMonths(i * 6L);
            LocalDate periodEnd = periodStart.plusMonths(6).minusDays(1);
            periods.add(buildPeriod(fy, FiscalPeriodType.HALF_YEAR, periodStart, periodEnd));
        }

        // 1 YEAR period — the FY range itself.
        periods.add(buildPeriod(fy, FiscalPeriodType.YEAR, fy.getStartDate(), fy.getEndDate()));

        return periods;
    }

    private FiscalPeriod buildPeriod(FiscalYear fy, FiscalPeriodType type, LocalDate start, LocalDate end) {
        FiscalPeriod period = new FiscalPeriod();
        period.setFiscalYearId(fy.getId());
        period.setPeriodType(type);
        period.setStartDate(start);
        period.setEndDate(end);
        period.setStatus(FiscalPeriodStatus.OPEN);
        period.setCreatedBy(currentUser());
        return period;
    }

    // ── validation ───────────────────────────────────────────────────────────

    private void validateBounds(LocalDate startDate, LocalDate endDate) {
        if (startDate.getDayOfMonth() != 1) {
            throw new InvalidFiscalYearBoundsException(
                "startDate must be the first day of a month", startDate, endDate);
        }
        LocalDate expectedEnd = startDate.plusYears(1).minusDays(1);
        if (!endDate.equals(expectedEnd)) {
            throw new InvalidFiscalYearBoundsException(
                "endDate must be exactly 12 months minus one day after startDate (expected "
                    + expectedEnd + ")",
                startDate, endDate);
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private FiscalYear loadOrThrow(UUID id) {
        return fiscalYearRepository.findByIdAndDeletedAtIsNull(id)
            .orElseThrow(() -> new FiscalYearNotFoundException(id));
    }

    private Optional<FiscalYear> findActiveOrNull() {
        List<FiscalYear> actives = fiscalYearRepository.findByStatusAndDeletedAtIsNull(FiscalYearStatus.ACTIVE);
        if (actives.isEmpty()) {
            return Optional.empty();
        }
        if (actives.size() > 1) {
            // Service-layer invariant violated (V31 has no DB UNIQUE on status).
            // Surfacing as IllegalStateException because this is a programmer bug,
            // not a user-supplied input problem.
            throw new IllegalStateException(
                "Multiple ACTIVE fiscal years exist (count=" + actives.size() +
                    "); FiscalYearService.activate invariant violated.");
        }
        return Optional.of(actives.get(0));
    }

    private List<FiscalPeriodResponse> listPeriodResponses(FiscalYear fy) {
        return fiscalPeriodRepository
            .findByFiscalYearIdAndDeletedAtIsNullOrderByStartDateAscPeriodTypeAsc(fy.getId())
            .stream()
            .sorted(Comparator
                .comparing(FiscalPeriod::getPeriodType)
                .thenComparing(FiscalPeriod::getStartDate))
            .map(this::toPeriodResponse)
            .toList();
    }

    private String currentUser() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null ? auth.getName() : "system";
    }

    private FiscalYearResponse toResponse(FiscalYear fy, List<FiscalPeriodResponse> periods) {
        return new FiscalYearResponse(
            fy.getId(),
            fy.getName(),
            fy.getStartDate(),
            fy.getEndDate(),
            fy.getStatus(),
            fy.getCreatedAt(),
            periods
        );
    }

    private FiscalPeriodResponse toPeriodResponse(FiscalPeriod period) {
        return new FiscalPeriodResponse(
            period.getId(),
            period.getFiscalYearId(),
            period.getPeriodType(),
            period.getStartDate(),
            period.getEndDate(),
            period.getStatus(),
            period.getSoftClosedAt(),
            period.getHardClosedAt()
        );
    }
}
