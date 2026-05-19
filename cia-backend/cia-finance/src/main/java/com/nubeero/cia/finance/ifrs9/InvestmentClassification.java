package com.nubeero.cia.finance.ifrs9;

/**
 * IFRS 9 §4.1 classification categories for financial assets.
 *
 * <ul>
 *   <li>{@link #AMORTISED_COST} — §4.1.2: business model is held-to-collect
 *       AND SPPI test passes. Carrying value = amortised cost (effective
 *       interest method); ECL applied; interest income to P&amp;L (4210).</li>
 *   <li>{@link #FVOCI_DEBT} — §4.1.2A: business model is held-to-collect-
 *       and-sell AND SPPI test passes. FV on the BS; FV changes to OCI;
 *       interest income to P&amp;L; ECL applied; reclassified to P&amp;L
 *       on derecognition (recycling).</li>
 *   <li>{@link #FVOCI_EQUITY} — §5.7.5: equity instrument irrevocably
 *       elected at inception. FV on the BS; FV changes to OCI;
 *       <em>no recycling</em> on derecognition (§B5.7.1).</li>
 *   <li>{@link #FVPL} — §4.1.4: catch-all (SPPI fails, business model is
 *       sell-first, or §4.1.5 irrevocable designation). FV on the BS;
 *       all changes (including dividends/interest) to P&amp;L.</li>
 * </ul>
 *
 * <p>Values match {@code ck_investment_classification} in V39.
 */
public enum InvestmentClassification {
    AMORTISED_COST,
    FVOCI_DEBT,
    FVOCI_EQUITY,
    FVPL
}
