package com.corebanking.funds.domain;

/**
 * Contractual basis of a product version. NON_INTEREST is not CONVENTIONAL at a zero rate; it
 * needs its own approved contract and profit/fee mechanics, so the two never interchange.
 */
public enum FinancePrinciple {
    CONVENTIONAL, NON_INTEREST
}
