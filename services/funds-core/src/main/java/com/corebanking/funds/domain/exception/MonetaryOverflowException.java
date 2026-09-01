package com.corebanking.funds.domain.exception;

/**
 * A money amount left the signed 64-bit range. Raised by checked arithmetic in Money,
 * NormalBalance, journal totals and balance updates, and by SQLSTATE 22003 from the
 * materialised-balance and control-projection updates. There is no saturation or rounding
 * fallback; the posting transaction rolls back.
 */
public class MonetaryOverflowException extends RuntimeException {
    public MonetaryOverflowException(ArithmeticException cause) {
        super(cause);
    }
}
