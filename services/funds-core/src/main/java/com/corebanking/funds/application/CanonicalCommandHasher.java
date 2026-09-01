package com.corebanking.funds.application;

import com.corebanking.funds.domain.JournalDraft;
import com.corebanking.funds.domain.ReversalRequest;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Versioned, domain-separated hashes for typed financial commands, i.e. request_hash_scheme
 * TYPED_V2. Every digest starts with a domain string, so a posting hash and a reversal hash can
 * never coincide for the same bytes; fields use the same length-prefixed encoding as
 * CanonicalJournalHasher but are never null. The legacy V004_OPAQUE scheme has no algorithm
 * here: such commands are replayed by rebuilding a typed hash from verified stored facts.
 */
public final class CanonicalCommandHasher {
    private static final String POSTING_V2 = "funds-core/posting-command/v2";
    private static final String REVERSAL_V2 = "funds-core/reversal-command/v2";
    private final CanonicalJournalHasher journalHasher = new CanonicalJournalHasher();

    /**
     * Request hash of a generic posting: the domain plus the V2 journal hash, so it covers
     * every financial field of the journal without a second field list to keep in sync.
     */
    public String postingV2(JournalDraft journal) {
        Objects.requireNonNull(journal, "journal");
        var digest = digest();
        field(digest, "domain", POSTING_V2);
        field(digest, "journalHash", journalHasher.v2Sha256(journal));
        return HexFormat.of().formatHex(digest.digest());
    }

    /**
     * Request hash of a reversal: every ReversalRequest field except the hash itself, so a
     * replay that changes any field is an idempotency conflict rather than a second reversal.
     */
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
