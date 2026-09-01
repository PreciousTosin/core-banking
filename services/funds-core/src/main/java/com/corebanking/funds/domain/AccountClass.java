package com.corebanking.funds.domain;

/**
 * Position in the accounting equation. ASSET and EXPENSE are debit-normal; LIABILITY, EQUITY
 * and INCOME are credit-normal. Stored per chart mapping with the same CHECK list.
 */
public enum AccountClass {
    ASSET, LIABILITY, EQUITY, INCOME, EXPENSE
}
