package com.nubeero.cia.finance.gl;

/**
 * Lifecycle state of a journal entry.
 *
 * <ul>
 *   <li>{@code DRAFT} — reserved for future workflow (multi-step authoring).
 *     Slice 1.4 never writes this value; it exists in the CHECK constraint
 *     so later slices can introduce draft workflows without a migration.</li>
 *   <li>{@code POSTED} — the entry is part of the GL and affects trial
 *     balance results. All entries created by {@code post(...)} land here.
 *     Reversal entries are also {@code POSTED} (per D2=A) — they're real
 *     postings that happen to undo the original.</li>
 *   <li>{@code REVERSED} — terminal: the entry has been reversed by a later
 *     mirror entry. The reversal entry's {@code reversal_of} FK points back
 *     to this row.</li>
 * </ul>
 *
 * <p>D2=A — Original POSTED → REVERSED; reversal JE is itself POSTED.
 */
public enum JournalEntryStatus {
    DRAFT,
    POSTED,
    REVERSED
}
