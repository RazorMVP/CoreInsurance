package com.nubeero.cia.finance.gl;

import com.nubeero.cia.common.exception.BusinessRuleException;
import com.nubeero.cia.finance.dto.JournalEntryLineRequest;
import com.nubeero.cia.finance.dto.JournalEntryLineResponse;
import com.nubeero.cia.finance.dto.JournalEntryResponse;
import com.nubeero.cia.finance.dto.PostJournalEntryRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

/**
 * Single authoritative path for inserting and reversing journal entries.
 *
 * <p>Slice 1.4 (gateway). Every later closure slice posts through this
 * service — direct INSERTs on {@code journal_entry} / {@code journal_entry_line}
 * are forbidden in business code. Centralising posting here gives us one
 * place to enforce:
 * <ul>
 *   <li>D6 — debits must equal credits before any INSERT.</li>
 *   <li>D7 — destination accounts must be active (skipped for reversals).</li>
 *   <li>D8 — idempotency via the {@code (sourceModule, sourceEventType,
 *       sourceReference)} triple, with an advisory read so duplicates
 *       surface as a clean 409.</li>
 *   <li>D11 — single-reversal rule: a {@code REVERSED} entry, or a reversal
 *       entry, cannot be reversed again.</li>
 * </ul>
 *
 * <p>Reversal model (D2=A): the original transitions to status
 * {@code REVERSED}; a new {@link JournalEntry} is posted with mirror lines
 * (debit ↔ credit, same accounts, same amounts), {@code status=POSTED},
 * {@code reversal_of=originalId}. Both rows remain in the GL; trial balance
 * picks them up cumulatively and they cancel. The reversal's
 * {@code businessDate} defaults to today (d5) — this anchors the reversal
 * to the period it was decided in, which is what auditors expect.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class JournalEntryService {

    /**
     * Source-event marker used on reversal entries. Filtering on this value
     * gives a clean "list every reversal" inquiry without parsing
     * narratives.
     */
    public static final String REVERSAL_EVENT_TYPE = "REVERSAL";

    private final JournalEntryRepository repository;
    private final ChartOfAccountService chartOfAccountService;
    private final FiscalPeriodResolver fiscalPeriodResolver;
    private final Clock clock;

    public JournalEntryResponse findById(UUID id) {
        JournalEntry je = repository.findByIdAndDeletedAtIsNull(id)
            .orElseThrow(() -> new JournalEntryNotFoundException(id));
        return toResponse(je);
    }

    /**
     * Posts a new journal entry. All validation happens before any database
     * write — if the request is malformed the GL is unchanged.
     *
     * <p>Order of checks (each fail-fast):
     * <ol>
     *   <li>Line amount XOR (exactly one of debit/credit &gt; 0 per line).</li>
     *   <li>Currency code populated (defaults to NGN if blank).</li>
     *   <li>Σ debits == Σ credits ({@link UnbalancedJournalEntryException}).</li>
     *   <li>Idempotency triple is free ({@link JournalEntryDuplicateException}).</li>
     *   <li>Fiscal MONTH period exists for the business date
     *       ({@link FiscalPeriodNotFoundException}).</li>
     *   <li>Every COA code resolves and is active
     *       ({@link InactiveAccountException}).</li>
     * </ol>
     */
    @Transactional
    public JournalEntryResponse post(PostJournalEntryRequest request) {
        validateLineAmounts(request.lines());

        BigDecimal totalDebits = sum(request.lines(), JournalEntryLineRequest::debitAmount);
        BigDecimal totalCredits = sum(request.lines(), JournalEntryLineRequest::creditAmount);
        if (totalDebits.compareTo(totalCredits) != 0) {
            throw new UnbalancedJournalEntryException(totalDebits, totalCredits);
        }

        repository.findBySourceModuleAndSourceEventTypeAndSourceReference(
                request.sourceModule(), request.sourceEventType(), request.sourceReference())
            .ifPresent(existing -> {
                throw new JournalEntryDuplicateException(
                    request.sourceModule(), request.sourceEventType(), request.sourceReference());
            });

        UUID periodId = fiscalPeriodResolver.resolveMonthIdForBusinessDate(request.businessDate());

        JournalEntry je = newHeader(request, periodId);
        int lineNo = 1;
        for (JournalEntryLineRequest lineReq : request.lines()) {
            ChartOfAccount account = chartOfAccountService.findByCode(lineReq.accountCode());
            if (!account.isActive()) {
                throw new InactiveAccountException(account.getCode());
            }
            je.addLine(buildLine(lineReq, account, lineNo++));
        }

        JournalEntry saved = repository.save(je);
        return toResponse(saved);
    }

    /**
     * Reverses a posted journal entry by recording a mirror posting and
     * transitioning the original to {@link JournalEntryStatus#REVERSED}.
     *
     * <p>D5/d10: the reversal uses today as both business and posting date
     * and narrative {@code "REVERSAL of JE {id}: {reason}"}. The reversal
     * itself is {@code POSTED} (D2=A) so trial balance picks both rows up
     * and they cancel.
     *
     * <p>Single-reversal rule (d11): refuses to reverse an entry that is
     * already {@code REVERSED} or itself a reversal.
     *
     * <p>Inactive accounts on the original do <strong>not</strong> block
     * the reversal (d7). The user has no recourse against an in-flight
     * cleanup if we did — the original posting already happened and the GL
     * must net to zero.
     */
    @Transactional
    public JournalEntryResponse reverse(UUID originalId, String reason) {
        JournalEntry original = repository.findByIdAndDeletedAtIsNull(originalId)
            .orElseThrow(() -> new JournalEntryNotFoundException(originalId));

        if (original.getStatus() == JournalEntryStatus.REVERSED) {
            throw new JournalEntryAlreadyReversedException(originalId);
        }
        if (original.getReversalOf() != null) {
            throw new JournalEntryAlreadyReversedException(originalId);
        }
        if (original.getStatus() != JournalEntryStatus.POSTED) {
            throw new BusinessRuleException(
                "JOURNAL_ENTRY_NOT_POSTED",
                "Only POSTED journal entries can be reversed (current status: " + original.getStatus() + ")");
        }

        LocalDate today = LocalDate.now(clock);
        UUID periodId = fiscalPeriodResolver.resolveMonthIdForBusinessDate(today);

        JournalEntry reversal = new JournalEntry();
        reversal.setPostingDate(today);
        reversal.setBusinessDate(today);
        reversal.setPeriodId(periodId);
        reversal.setSourceModule(original.getSourceModule());
        reversal.setSourceEventType(REVERSAL_EVENT_TYPE);
        reversal.setSourceReference(original.getId().toString());
        reversal.setNarrative("REVERSAL of JE " + original.getId() + ": " + reason);
        reversal.setPostedBy(currentUser());
        reversal.setStatus(JournalEntryStatus.POSTED);
        reversal.setReversalOf(original.getId());
        reversal.setCreatedBy(currentUser());

        int lineNo = 1;
        for (JournalEntryLine originalLine : original.getLines()) {
            JournalEntryLine mirror = new JournalEntryLine();
            mirror.setLineNo(lineNo++);
            mirror.setAccount(originalLine.getAccount());
            mirror.setDebitAmount(originalLine.getCreditAmount());
            mirror.setCreditAmount(originalLine.getDebitAmount());
            mirror.setCurrencyCode(originalLine.getCurrencyCode());
            mirror.setCohortYear(originalLine.getCohortYear());
            mirror.setPortfolioId(originalLine.getPortfolioId());
            mirror.setContractGroupId(originalLine.getContractGroupId());
            mirror.setHoldingId(originalLine.getHoldingId());
            mirror.setDimensionTags(new HashMap<>(originalLine.getDimensionTags()));
            reversal.addLine(mirror);
        }

        original.setStatus(JournalEntryStatus.REVERSED);
        repository.save(original);
        JournalEntry savedReversal = repository.save(reversal);
        return toResponse(savedReversal);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private void validateLineAmounts(List<JournalEntryLineRequest> lines) {
        for (int i = 0; i < lines.size(); i++) {
            JournalEntryLineRequest line = lines.get(i);
            boolean hasDebit = line.debitAmount().compareTo(BigDecimal.ZERO) > 0;
            boolean hasCredit = line.creditAmount().compareTo(BigDecimal.ZERO) > 0;
            if (hasDebit == hasCredit) {
                throw new BusinessRuleException(
                    "JOURNAL_ENTRY_INVALID_LINE",
                    "Line " + (i + 1) + " (" + line.accountCode() + "): exactly one of debit/credit must be > 0");
            }
            if (line.debitAmount().scale() > 2 || line.creditAmount().scale() > 2) {
                throw new BusinessRuleException(
                    "JOURNAL_ENTRY_INVALID_SCALE",
                    "Line " + (i + 1) + " (" + line.accountCode() + "): amounts must have at most 2 decimal places");
            }
        }
    }

    private BigDecimal sum(List<JournalEntryLineRequest> lines, java.util.function.Function<JournalEntryLineRequest, BigDecimal> picker) {
        return lines.stream().map(picker).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private JournalEntry newHeader(PostJournalEntryRequest request, UUID periodId) {
        LocalDate today = LocalDate.now(clock);
        JournalEntry je = new JournalEntry();
        je.setPostingDate(today);
        je.setBusinessDate(request.businessDate());
        je.setPeriodId(periodId);
        je.setSourceModule(request.sourceModule());
        je.setSourceEventType(request.sourceEventType());
        je.setSourceReference(request.sourceReference());
        je.setNarrative(request.narrative());
        je.setPostedBy(currentUser());
        je.setStatus(JournalEntryStatus.POSTED);
        je.setCreatedBy(currentUser());
        return je;
    }

    private JournalEntryLine buildLine(JournalEntryLineRequest req, ChartOfAccount account, int lineNo) {
        JournalEntryLine line = new JournalEntryLine();
        line.setLineNo(lineNo);
        line.setAccount(account);
        line.setDebitAmount(req.debitAmount());
        line.setCreditAmount(req.creditAmount());
        line.setCurrencyCode(req.currencyCode() != null && !req.currencyCode().isBlank() ? req.currencyCode() : "NGN");
        line.setCohortYear(req.cohortYear());
        line.setPortfolioId(req.portfolioId());
        line.setContractGroupId(req.contractGroupId());
        line.setHoldingId(req.holdingId());
        if (req.dimensionTags() != null) {
            line.setDimensionTags(new HashMap<>(req.dimensionTags()));
        }
        return line;
    }

    private String currentUser() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null ? auth.getName() : "system";
    }

    private JournalEntryResponse toResponse(JournalEntry je) {
        List<JournalEntryLineResponse> lineResponses = new ArrayList<>(je.getLines().size());
        for (JournalEntryLine line : je.getLines()) {
            ChartOfAccount account = line.getAccount();
            lineResponses.add(new JournalEntryLineResponse(
                line.getId(),
                line.getLineNo(),
                account.getId(),
                account.getCode(),
                account.getName(),
                line.getDebitAmount(),
                line.getCreditAmount(),
                line.getCurrencyCode(),
                line.getCohortYear(),
                line.getPortfolioId(),
                line.getContractGroupId(),
                line.getHoldingId(),
                line.getDimensionTags()
            ));
        }
        return new JournalEntryResponse(
            je.getId(),
            je.getPostingDate(),
            je.getBusinessDate(),
            je.getPeriodId(),
            je.getSourceModule(),
            je.getSourceEventType(),
            je.getSourceReference(),
            je.getNarrative(),
            je.getPostedBy(),
            je.getStatus(),
            je.getReversalOf(),
            je.getCreatedAt(),
            lineResponses
        );
    }
}
