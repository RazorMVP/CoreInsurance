package com.nubeero.cia.finance.gl;

/**
 * The five top-level chart-of-accounts classes. Stored as a VARCHAR(20) on
 * {@code chart_of_account.account_type} and enforced by V31's
 * {@code ck_chart_of_account_type} CHECK constraint.
 */
public enum AccountType {
    ASSET,
    LIABILITY,
    EQUITY,
    INCOME,
    EXPENSE
}
