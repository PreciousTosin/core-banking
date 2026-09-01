package com.corebanking.funds.application;

import com.corebanking.funds.domain.JournalDraft;
import com.corebanking.funds.domain.PostingLine;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Canonical SHA-256 of a journal's financial facts. Each field is fed to the digest as a
 * length-prefixed name, a one-byte null tag, then the length-prefixed UTF-8 of its string form,
 * so field boundaries and absent values can never be confused. Postings are hashed in
 * (accountId, postingId) string order and dimensions in key order, independent of input order.
 * Two schemes exist and the persisted canonical_hash_scheme picks the verifier: V2 is the
 * current writer and pins chartVersionId; V004_V1 verifies facts written before V005 and has
 * no chart field. accountSequence is deliberately not hashed, so the digest is identical
 * before and after the repository assigns sequences.
 */
public class CanonicalJournalHasher {
    // UUID string order, the same order the repository locks accounts in; not UUID.compareTo.
    private static final Comparator<UUID> UUID_CANONICAL_ORDER =
        Comparator.comparing(UUID::toString);
    private static final Comparator<PostingLine> POSTING_ORDER = Comparator
        .comparing(PostingLine::accountId, Comparator.nullsFirst(UUID_CANONICAL_ORDER))
        .thenComparing(PostingLine::postingId, Comparator.nullsFirst(UUID_CANONICAL_ORDER));

    /** Alias for the current (V2) scheme. */
    public String sha256(JournalDraft draft) {
        return v2Sha256(draft);
    }

    /** Exact verifier for journal facts written by the V004 canonical algorithm. */
    public String v004Sha256(JournalDraft draft) {
        return hash(draft, false);
    }

    /** Current journal-fact hash. V2 pins the governed chart version. */
    public String v2Sha256(JournalDraft draft) {
        return hash(draft, true);
    }

    private static String hash(JournalDraft draft, boolean includeChartVersion) {
        Objects.requireNonNull(draft, "draft");
        var encoder = new CanonicalEncoder(sha256Digest());

        encoder.field("journalId", draft.journalId());
        encoder.field("commandId", draft.commandId());
        encoder.field("correlationId", draft.correlationId());
        encoder.field("businessTransactionId", draft.businessTransactionId());
        encoder.field("legalEntityId", draft.legalEntityId());
        encoder.field("bookId", draft.bookId());
        // The only V004_V1 / V2 difference: V2 binds the fact to the governed chart it was
        // posted under.
        if (includeChartVersion) {
            encoder.field("chartVersionId", draft.chartVersionId());
        }
        encoder.field("periodId", draft.periodId());
        encoder.field("transactionType", draft.transactionType());
        encoder.field("narration", draft.narration());
        encoder.field("bookingTime", draft.bookingTime());
        encoder.field("valueDate", draft.valueDate());
        encoder.field("reversalOfJournalId", draft.reversalOfJournalId());
        encoder.field("policyVersion", draft.policyVersion());
        encoder.field("postingCount", draft.postings().size());

        draft.postings().stream().sorted(POSTING_ORDER).forEach(posting -> {
            encoder.field("postingId", posting.postingId());
            encoder.field("accountId", posting.accountId());
            encoder.field("currency", posting.currency().value());
            encoder.field("signedMinorUnits", posting.signedMinorUnits());
            encoder.field("dimensionCount", posting.dimensions().size());
            posting.dimensions().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(dimension -> {
                    encoder.field("dimensionKey", dimension.getKey());
                    encoder.field("dimensionValue", dimension.getValue());
                });
        });

        return HexFormat.of().formatHex(encoder.digest());
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    /** Length-prefixed, null-tagged field encoder feeding a single SHA-256 digest. */
    private static final class CanonicalEncoder {
        private final MessageDigest digest;

        private CanonicalEncoder(MessageDigest digest) {
            this.digest = digest;
        }

        private void field(String name, Object value) {
            bytes(name.getBytes(StandardCharsets.UTF_8));
            digest.update(value == null ? (byte) 0 : (byte) 1);
            if (value != null) {
                bytes(value.toString().getBytes(StandardCharsets.UTF_8));
            }
        }

        private void bytes(byte[] value) {
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(value.length).array());
            digest.update(value);
        }

        private byte[] digest() {
            return digest.digest();
        }
    }
}
