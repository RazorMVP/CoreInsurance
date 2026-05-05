package com.nubeero.cia.policy;

/**
 * Lifecycle of a pre-loss policy survey.
 *
 * <ul>
 *   <li>{@link #ASSIGNED} — surveyor selected; awaiting report.</li>
 *   <li>{@link #REPORT_SUBMITTED} — report has been recorded; awaiting approval.</li>
 *   <li>{@link #APPROVED} — terminal: survey accepted; policy may proceed.</li>
 *   <li>{@link #OVERRIDDEN} — terminal: survey requirement waived with a reason.</li>
 * </ul>
 *
 * Re-assignment of a surveyor while in ASSIGNED or REPORT_SUBMITTED simply
 * updates the existing row back to ASSIGNED.
 */
public enum SurveyStatus {
    ASSIGNED,
    REPORT_SUBMITTED,
    APPROVED,
    OVERRIDDEN,
}
