package com.corebanking.funds.domain;

/** Compatibility facade for callers in the domain package. */
public class MonetaryOverflowException
        extends com.corebanking.funds.domain.exception.MonetaryOverflowException {
    public MonetaryOverflowException(ArithmeticException cause) {
        super(cause);
    }
}
