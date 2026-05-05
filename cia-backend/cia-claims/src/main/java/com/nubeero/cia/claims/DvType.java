package com.nubeero.cia.claims;

/**
 * Discharge Voucher type — captured when the claim is approved for settlement.
 *
 * <ul>
 *   <li>{@code OWN_DAMAGE}  — payment to the insured for damage to their own property/vehicle.</li>
 *   <li>{@code THIRD_PARTY} — payment for third-party bodily injury or property damage.</li>
 *   <li>{@code EX_GRATIA}   — discretionary payment outside strict policy terms.</li>
 * </ul>
 */
public enum DvType {
    OWN_DAMAGE,
    THIRD_PARTY,
    EX_GRATIA
}
