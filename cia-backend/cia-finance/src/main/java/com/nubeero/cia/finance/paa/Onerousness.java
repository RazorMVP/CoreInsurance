package com.nubeero.cia.finance.paa;

/**
 * IFRS 17 §16 onerousness classification for a group of contracts at initial
 * recognition. Per §22 a contract's bucket is fixed at recognition — onerousness
 * never changes by moving the contract; if measurement later finds a loss, a
 * loss component is recognised against the bucket the contract was originally
 * assigned to.
 *
 * <p>Values match {@code ck_group_onerousness} in V36.
 */
public enum Onerousness {

    /** Contracts that are not onerous at initial recognition and have no significant possibility of becoming onerous (§16(a)). */
    NOT_ONEROUS,

    /** Contracts that have no significant possibility of becoming onerous subsequently — the §16(b) middle bucket. */
    NO_SIGNIFICANT_POSSIBILITY,

    /** Contracts onerous at initial recognition (§16(c)). */
    ONEROUS
}
