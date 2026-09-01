package com.corebanking.funds.domain;

/**
 * Commercial kind of a customer deposit product. Lives on the immutable ProductVersion, not
 * the definition, so a new version can never reclassify accounts bound to an older one.
 */
public enum DepositProductKind {
    SAVINGS, CURRENT, FIXED_DEPOSIT, DOMICILIARY
}
