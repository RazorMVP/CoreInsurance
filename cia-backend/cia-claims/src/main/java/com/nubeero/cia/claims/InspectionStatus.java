package com.nubeero.cia.claims;

/**
 * Lifecycle of a post-loss claim inspection.
 *
 * <ul>
 *   <li>{@link #ASSIGNED} — surveyor selected; awaiting report.</li>
 *   <li>{@link #REPORT_SUBMITTED} — report has been recorded; awaiting approval.</li>
 *   <li>{@link #APPROVED} — terminal: inspection accepted; claim may proceed.</li>
 *   <li>{@link #DECLINED} — non-terminal: report rejected; surveyor must re-submit
 *       or be re-assigned. Status returns to ASSIGNED on next assignment.</li>
 *   <li>{@link #OVERRIDDEN} — terminal: inspection requirement waived with a reason.</li>
 * </ul>
 *
 * <p>Differs from PolicySurvey (B4.3) by the additional DECLINED transition —
 * post-loss inspection reports are commonly bounced for incomplete or
 * inconsistent findings, then re-submitted; pre-loss surveys are simpler.
 */
public enum InspectionStatus {
    ASSIGNED,
    REPORT_SUBMITTED,
    APPROVED,
    DECLINED,
    OVERRIDDEN,
}
