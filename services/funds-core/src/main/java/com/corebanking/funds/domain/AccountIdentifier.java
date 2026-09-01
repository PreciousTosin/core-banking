package com.corebanking.funds.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * An address that resolves to a ledger account. The ledger-account UUID is the balance-bearing
 * identity; this record holds no balance and is never used as a posting account, command or
 * idempotency key. Scheme-specific shape is enforced here and mirrored by the
 * account_identifier CHECK constraints and funds.is_valid_nuban in V001. Lifecycle status and
 * routing scope are validated only by the database CHECK lists, not by this record.
 */
public record AccountIdentifier(
    UUID id,
    UUID ledgerAccountId,
    AccountIdentifierScheme scheme,
    String normalisedValue,
    String institutionCode,
    UUID providerId,
    String lifecycleStatus,
    boolean primary,
    String routingScope) {
    // Weights over the 6-digit institution code followed by the 9-digit serial. Must stay
    // identical to the coefficients in funds.is_valid_nuban (V001) or Java and SQL disagree.
    private static final int[] NUBAN_WEIGHTS = {3, 7, 3, 3, 7, 3, 3, 7, 3, 3, 7, 3, 3, 7, 3};

    public AccountIdentifier {
        id = Objects.requireNonNull(id, "id");
        ledgerAccountId = Objects.requireNonNull(ledgerAccountId, "ledgerAccountId");
        scheme = Objects.requireNonNull(scheme, "scheme");
        normalisedValue = requireNonBlank(normalisedValue, "normalisedValue");
        lifecycleStatus = requireNonBlank(lifecycleStatus, "lifecycleStatus");
        routingScope = requireNonBlank(routingScope, "routingScope");

        // Each scheme is exclusive about its qualifier: a NUBAN is scoped by institution code
        // and a provider virtual account by providerId. Mixing them would let one address
        // resolve under two scopes, which the active-scope unique index relies on excluding.
        switch (scheme) {
            case NUBAN -> {
                institutionCode = requireInstitutionCode(institutionCode);
                if (providerId != null) {
                    throw new IllegalArgumentException("NUBAN must not have a providerId");
                }
                if (!isValidNuban(institutionCode, normalisedValue)) {
                    throw new IllegalArgumentException("invalid NUBAN");
                }
            }
            case PROVIDER_VIRTUAL_ACCOUNT -> {
                if (providerId == null) {
                    throw new IllegalArgumentException("provider virtual account requires providerId");
                }
            }
            case IBAN -> throw new IllegalArgumentException("IBAN is unsupported until a country-specific validator exists");
        }
    }

    /**
     * Tenth NUBAN digit for a six-digit institution code and nine-digit serial: weighted sum
     * mod 10, subtracted from 10, with 10 folding to 0.
     */
    public static char nubanCheckDigit(String institutionCode, String serial) {
        institutionCode = requireInstitutionCode(institutionCode);
        if (serial == null || !serial.matches("[0-9]{9}")) {
            throw new IllegalArgumentException("NUBAN serial must be nine digits");
        }

        var input = institutionCode + serial;
        var total = 0;
        for (var index = 0; index < NUBAN_WEIGHTS.length; index++) {
            total += Character.digit(input.charAt(index), 10) * NUBAN_WEIGHTS[index];
        }
        var checkDigit = 10 - total % 10;
        return (char) ('0' + (checkDigit == 10 ? 0 : checkDigit));
    }

    /** Shape plus check-digit test; returns false rather than throwing so callers can probe. */
    public static boolean isValidNuban(String institutionCode, String nuban) {
        if (institutionCode == null || !institutionCode.matches("[0-9]{6}")
            || nuban == null || !nuban.matches("[0-9]{10}")) {
            return false;
        }
        return nubanCheckDigit(institutionCode, nuban.substring(0, 9)) == nuban.charAt(9);
    }

    private static String requireInstitutionCode(String institutionCode) {
        if (institutionCode == null || !institutionCode.matches("[0-9]{6}")) {
            throw new IllegalArgumentException("institutionCode must be six digits");
        }
        return institutionCode;
    }

    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
