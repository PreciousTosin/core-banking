package com.corebanking.funds.domain;

/**
 * Address scheme for resolving to a ledger account. NUBAN carries an institution code,
 * PROVIDER_VIRTUAL_ACCOUNT a provider ID. IBAN is reserved only; both the record and the
 * account_identifier CHECK reject it until a country-specific validator exists.
 */
public enum AccountIdentifierScheme {
    NUBAN, PROVIDER_VIRTUAL_ACCOUNT, IBAN
}
