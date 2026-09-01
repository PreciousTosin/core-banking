package com.corebanking.funds.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.RecordComponent;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * ACC-38 foundation portion: the NUBAN check-digit algorithm (six-digit institution code plus
 * nine-digit serial), per-scheme construction rules for AccountIdentifier, and the rule that an
 * identifier is an address only and never carries money or balance. Catches a check-digit
 * regression, an IBAN fabrication slipping through, or a balance field leaking onto the address.
 */
class NubanTest {
    @Test void validatesPublishedCbnAlgorithmFixture() {
        assertEquals('9', AccountIdentifier.nubanCheckDigit("000011", "000001457"));
        assertTrue(AccountIdentifier.isValidNuban("000011", "0000014579"));
    }

    @Test void validatesGeneratedSerialAndRejectsMutatedDigit() {
        var institutionCode = "011000";
        var serial = "987654321";
        var nuban = "9876543215";

        assertEquals('5', AccountIdentifier.nubanCheckDigit(institutionCode, serial));
        assertTrue(AccountIdentifier.isValidNuban(institutionCode, nuban));
        assertFalse(AccountIdentifier.isValidNuban(institutionCode, "9876543205"));
    }

    @Test void rejectsNonDigitsAndKeepsSyntheticFixtureValid() {
        assertFalse(AccountIdentifier.isValidNuban("05800X", "0012345672"));
        assertFalse(AccountIdentifier.isValidNuban("058000", "00123456X2"));
        // Institution 000000 / NUBAN 0000000017 is the deterministic SIMULATOR_ONLY fixture from
        // the README; it must stay algorithmically valid without being production-routable.
        assertTrue(AccountIdentifier.isValidNuban("000000", "0000000017"));
    }

    @Test void requiresNubanInstitutionScopeAndProviderScopeForVirtualAccounts() {
        assertThrows(IllegalArgumentException.class, () -> identifier(
            AccountIdentifierScheme.NUBAN, "0000000017", null, null));
        assertThrows(IllegalArgumentException.class, () -> identifier(
            AccountIdentifierScheme.PROVIDER_VIRTUAL_ACCOUNT, "alias-001", null, null));
        assertThrows(IllegalArgumentException.class, () -> identifier(
            AccountIdentifierScheme.IBAN, "NG0000000017", null, null));
    }

    @Test void remainsAddressMetadataWithoutMoneyOrBalanceComponents() {
        assertTrue(AccountIdentifier.class.isRecord());
        for (RecordComponent component : AccountIdentifier.class.getRecordComponents()) {
            assertFalse(component.getName().toLowerCase().contains("balance"));
            assertFalse(Money.class.isAssignableFrom(component.getType()));
        }
    }

    // Every identifier built here is routed SIMULATOR_ONLY (the routing_scope CHECK value in V001),
    // so a fixture can never be mistaken for a production-routable address.
    private static AccountIdentifier identifier(
        AccountIdentifierScheme scheme, String value, String institutionCode, UUID providerId) {
        return new AccountIdentifier(
            UUID.randomUUID(), UUID.randomUUID(), scheme, value, institutionCode, providerId, "ACTIVE", false, "SIMULATOR_ONLY");
    }
}
