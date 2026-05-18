package com.nubeero.cia.finance.ifrs9;

/**
 * IFRS 9 §5.5 Expected Credit Loss method election. Recorded per asset
 * class on {@link Ifrs9Config}.
 *
 * <ul>
 *   <li>{@link #GENERAL} — 3-stage model (§5.5.3-§5.5.8). Default for
 *       investment debt. 12-month ECL in stage 1; lifetime ECL in
 *       stages 2 and 3.</li>
 *   <li>{@link #SIMPLIFIED} — single-stage lifetime ECL via provision
 *       matrix (§5.5.15). Default for trade/premium receivables and
 *       contract assets.</li>
 * </ul>
 *
 * <p>Values match {@code ck_ifrs9_config_*_ecl_method} in V39.
 */
public enum EclMethod {
    GENERAL,
    SIMPLIFIED
}
