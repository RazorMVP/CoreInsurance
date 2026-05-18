package com.nubeero.cia.finance.gl;

import com.nubeero.cia.common.entity.BaseEntity;
import com.nubeero.cia.common.entity.LockableByPeriod;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Journal entry header. Posted by {@link JournalEntryService} as the single
 * authoritative path that inserts into the GL.
 *
 * <p>Two-date model (V31):
 * <ul>
 *   <li>{@code postingDate} — when the entry was recorded (DB default
 *       {@code current_date}).</li>
 *   <li>{@code businessDate} — when the underlying economic event occurred;
 *       drives period assignment via {@link FiscalPeriodResolver} and the
 *       5-business-day late-posting cut-off (Slice 1.7).</li>
 * </ul>
 * The CHECK constraint {@code ck_journal_entry_dates} forbids future-dated
 * postings ({@code businessDate <= postingDate}).
 *
 * <p>Idempotency: {@code (sourceModule, sourceEventType, sourceReference)} is
 * UNIQUE at the DB level. The {@code SubledgerPostingService} listeners
 * (Slice 1.5) rely on this to make replay safe — a duplicate insert errors
 * at the database, closing the TOCTOU window a service-layer existence check
 * would leave open. Manual postings receive a fresh UUID-derived reference
 * per d8 to avoid colliding with sub-ledger keys.
 *
 * <p>Reversal model (D2=A): when this entry is reversed, a new
 * {@link JournalEntry} is created with {@code reversalOf = this.id} and
 * status {@code POSTED}. The original transitions to {@link
 * JournalEntryStatus#REVERSED}. Both rows remain in the GL; trial balance
 * picks them up cumulatively and they cancel.
 */
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "journal_entry")
public class JournalEntry extends BaseEntity implements LockableByPeriod {

    @Column(name = "posting_date", nullable = false)
    private LocalDate postingDate;

    @Column(name = "business_date", nullable = false)
    private LocalDate businessDate;

    @Column(name = "period_id", nullable = false)
    private UUID periodId;

    @Column(name = "source_module", nullable = false, length = 40)
    private String sourceModule;

    @Column(name = "source_event_type", nullable = false, length = 60)
    private String sourceEventType;

    @Column(name = "source_reference", nullable = false, length = 100)
    private String sourceReference;

    @Column(name = "narrative")
    private String narrative;

    @Column(name = "posted_by", nullable = false, length = 100)
    private String postedBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private JournalEntryStatus status = JournalEntryStatus.POSTED;

    @Column(name = "reversal_of")
    private UUID reversalOf;

    // ── Slice 1.7c — Prior-Period Adjustment (IAS 8) markers ─────────────────
    // Set TRUE by the dedicated /journal-entries/prior-period-adjustment
    // endpoint (gated by FINANCE_APPROVE_PPA) so audit-found errors in closed
    // periods land in the OPEN period as PPAs instead of forcing a reopen.
    // Normal posts leave this FALSE and reason NULL — the V35 partial index
    // (idx_journal_entry_ppa) makes the PPA-filtered audit query fast.
    @Column(name = "prior_period_adjustment", nullable = false)
    private boolean priorPeriodAdjustment = false;

    @Column(name = "prior_period_adjustment_reason", columnDefinition = "TEXT")
    private String priorPeriodAdjustmentReason;

    /**
     * Lines belonging to this JE. Cascade ALL + orphan-removal aren't used —
     * the service inserts header then lines explicitly so the line FK can be
     * the persisted header id, and the DB ON DELETE CASCADE handles
     * removal if any future code path ever soft-deletes a header. Slice 1.4
     * never deletes a header in practice.
     */
    @OneToMany(mappedBy = "journalEntry", fetch = FetchType.LAZY, cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("lineNo ASC")
    private List<JournalEntryLine> lines = new ArrayList<>();

    public void addLine(JournalEntryLine line) {
        line.setJournalEntry(this);
        this.lines.add(line);
    }

    // ─── LockableByPeriod ─────────────────────────────────────────────────────
    // Slice 1.7: JournalEntry is the canary opt-in. businessDate is the
    // authoritative booking date for ledger-impact rows (postingDate is the
    // recording timestamp; businessDate drives period assignment). The
    // reversal carve-out hands the interceptor a "skip me" signal for entries
    // whose reversalOf is set — reversals are corrections and must remain
    // postable into the OPEN period even when the original sat in a now-closed
    // period (see CIAGB IAS-8-style guidance documented in Slice 1.7c).

    @Override
    public LocalDate getLockDate() {
        return businessDate;
    }

    @Override
    public boolean isReversal() {
        return reversalOf != null;
    }
}
