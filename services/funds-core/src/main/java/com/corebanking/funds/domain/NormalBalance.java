package com.corebanking.funds.domain;

import com.corebanking.funds.domain.exception.MonetaryOverflowException;

/**
 * Which accounting side increases an account. Debit-normal accounts (assets, expenses) grow
 * with positive postings; credit-normal accounts (liabilities, equity, income) grow with
 * negative ones. The multiplier turns a signed posting total into that natural balance.
 */
public enum NormalBalance {
    DEBIT(1), CREDIT(-1);

    private final int multiplier;

    NormalBalance(int multiplier) {
        this.multiplier = multiplier;
    }

    /**
     * Natural balance of a signed total. Multiplying by -1 overflows only for Long.MIN_VALUE,
     * which is still reported as MonetaryOverflowException rather than silently wrapping.
     */
    public long toNatural(long signedPostingTotal) {
        try {
            return Math.multiplyExact(signedPostingTotal, multiplier);
        } catch (ArithmeticException e) {
            throw new MonetaryOverflowException(e);
        }
    }
}
