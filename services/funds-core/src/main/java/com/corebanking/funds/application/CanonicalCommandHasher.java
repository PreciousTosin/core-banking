package com.corebanking.funds.application;

import com.corebanking.funds.domain.JournalDraft;
import com.corebanking.funds.domain.ReversalRequest;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** Versioned, domain-separated hashes for typed financial commands. */
public final class CanonicalCommandHasher {
    private static final String POSTING_V2 = "funds-core/posting-command/v2";
    private static final String REVERSAL_V2 = "funds-core/reversal-command/v2";
    private final CanonicalJournalHasher journalHasher = new CanonicalJournalHasher();

    public String postingV2(JournalDraft journal) {
        Objects.requireNonNull(journal, "journal");
        var digest = digest();
        field(digest, "domain", POSTING_V2);
        field(digest, "journalHash", journalHasher.v2Sha256(journal));
        return HexFormat.of().formatHex(digest.digest());
    }

    public String reversalV2(ReversalRequest request) {
        Objects.requireNonNull(request, "request");
        var digest = digest();
        field(digest, "domain", REVERSAL_V2);
        field(digest, "commandId", request.commandId());
        field(digest, "originalJournalId", request.originalJournalId());
        field(digest, "correlationId", request.correlationId());
        field(digest, "businessTransactionId", request.businessTransactionId());
        field(digest, "currentPeriodId", request.currentPeriodId());
        field(digest, "bookingTime", request.bookingTime());
        field(digest, "valueDate", request.valueDate());
        field(digest, "reason", request.reason());
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void field(MessageDigest digest, String name, Object value) {
        bytes(digest, name.getBytes(StandardCharsets.UTF_8));
        bytes(digest, Objects.requireNonNull(value, name).toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void bytes(MessageDigest digest, byte[] value) {
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(value.length).array());
        digest.update(value);
    }
}
