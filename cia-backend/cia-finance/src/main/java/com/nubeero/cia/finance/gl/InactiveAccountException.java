package com.nubeero.cia.finance.gl;

import com.nubeero.cia.common.exception.BusinessRuleException;

/**
 * Thrown by {@link JournalEntryService#post} when one or more lines target a
 * chart-of-account whose {@code isActive=false}. V31 deliberately keeps this
 * as a service-layer rule rather than a DB trigger: activity is a tenant
 * policy (an admin may temporarily inactivate an account during a balance
 * migration) and we want a clean 422 with a human-readable message rather
 * than a 500 from a trigger.
 *
 * <p>The reversal path skips this check (d7 — reversals always succeed,
 * even against now-inactive accounts) since the user has no recourse: the
 * original posting already happened and the GL must net to zero.
 */
public class InactiveAccountException extends BusinessRuleException {

    public InactiveAccountException(String accountCode) {
        super(
            "JOURNAL_ENTRY_INACTIVE_ACCOUNT",
            "Cannot post to inactive chart-of-account: " + accountCode);
    }
}
