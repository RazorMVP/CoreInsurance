package com.nubeero.cia.finance.gl;

import com.nubeero.cia.common.exception.CiaException;
import org.springframework.http.HttpStatus;

/**
 * Thrown when a sub-ledger event arrives at {@link SubledgerPostingService}
 * but no active {@link PostingRule} maps its event type to a COA debit /
 * credit pair (d7 — fail loud).
 *
 * <p>HTTP status 422 — the request is well-formed; the system has not been
 * configured to post this event. In a tenant-onboarded environment this
 * indicates a missing V33 seed row or an inactive rule that should be
 * re-activated by a System Admin.
 */
public class PostingRuleNotFoundException extends CiaException {

    public PostingRuleNotFoundException(String sourceEventType) {
        super(
            "POSTING_RULE_NOT_FOUND",
            "No active posting rule configured for event type: " + sourceEventType,
            HttpStatus.UNPROCESSABLE_ENTITY);
    }
}
