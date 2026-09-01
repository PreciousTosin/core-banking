package com.corebanking.funds.application.proof;

import com.corebanking.funds.domain.CurrencyCode;
import java.math.BigInteger;
import java.util.Objects;

public record ControlAccountProof(
    String controlCode,
    CurrencyCode currency,
    long cutoff,
    BigInteger sourceTotal,
    BigInteger projectionTotal,
    BigInteger difference
) {
    public ControlAccountProof {
        controlCode = requireControlCode(controlCode);
        currency = Objects.requireNonNull(currency, "currency");
        sourceTotal = Objects.requireNonNull(sourceTotal, "sourceTotal");
        projectionTotal = Objects.requireNonNull(projectionTotal, "projectionTotal");
        difference = Objects.requireNonNull(difference, "difference");
        if (cutoff < 0) {
            throw new IllegalArgumentException("cutoff must not be negative");
        }
        if (!difference.equals(sourceTotal.subtract(projectionTotal))) {
            throw new IllegalArgumentException("difference must equal sourceTotal - projectionTotal");
        }
    }

    static String requireControlCode(String controlCode) {
        Objects.requireNonNull(controlCode, "controlCode");
        if (controlCode.isBlank() || !controlCode.equals(controlCode.trim())) {
            throw new IllegalArgumentException("controlCode must be non-blank without surrounding whitespace");
        }
        return controlCode;
    }
}
