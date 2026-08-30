package com.corebanking.funds.domain.exception;

public class MonetaryOverflowException extends RuntimeException {
    public MonetaryOverflowException(ArithmeticException cause) {
        super(cause);
    }
}
