package com.corebanking.funds.domain;

import com.corebanking.funds.domain.exception.MonetaryOverflowException;

public enum NormalBalance {
    DEBIT(1), CREDIT(-1);

    private final int multiplier;

    NormalBalance(int multiplier) {
        this.multiplier = multiplier;
    }

    public long toNatural(long signedPostingTotal) {
        try {
            return Math.multiplyExact(signedPostingTotal, multiplier);
        } catch (ArithmeticException e) {
            throw new MonetaryOverflowException(e);
        }
    }
}
