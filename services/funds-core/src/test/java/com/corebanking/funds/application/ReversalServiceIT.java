package com.corebanking.funds.application;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.corebanking.funds.domain.CurrencyCode;
import com.corebanking.funds.domain.JournalDraft;
import com.corebanking.funds.domain.PostingLine;
import com.corebanking.funds.domain.ReversalRequest;
import com.corebanking.funds.domain.exception.AccountingPeriodClosedException;
import com.corebanking.funds.domain.exception.IdempotencyConflictException;
import com.corebanking.funds.domain.exception.InvalidJournalException;
import com.corebanking.funds.domain.exception.LedgerPersistenceException;
import com.corebanking.funds.infrastructure.postgres.JdbcLedgerRepository;
import com.corebanking.funds.infrastructure.postgres.PostgresRetryPolicy;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class ReversalServiceIT {
    private static final CurrencyCode NGN = CurrencyCode.of("NGN");
    private static final UUID ORIGINAL_COMMAND_ID = TestPostingStack.uuid(200);
    private static final UUID ORIGINAL_JOURNAL_ID = TestPostingStack.uuid(201);
    private static final UUID REVERSAL_COMMAND_ID = TestPostingStack.uuid(210);
    private static final UUID NEXT_PERIOD_ID = TestPostingStack.uuid(211);
    private static final Instant REVERSAL_BOOKING_TIME = Instant.parse("2026-02-15T09:30:00Z");
    private static final LocalDate REVERSAL_VALUE_DATE = LocalDate.of(2026, 2, 15);

    @Inject
    DataSource dataSource;

    @Inject
    PostingService postingService;

    @Inject
    ReversalService reversalService;

    @BeforeEach
    void setUp() throws SQLException {
        TestPostingStack.resetAndSeed(dataSource);
    }

    @AfterEach
    void tearDown() throws SQLException {
        TestPostingStack.reset(dataSource);
    }

    @Test
    void reversesClosedPeriodJournalInCurrentPeriodWithoutChangingOriginal() throws SQLException {
        postingService.post(exampleA(ORIGINAL_COMMAND_ID, ORIGINAL_JOURNAL_ID));
        JournalSnapshot originalBefore = journalSnapshot(ORIGINAL_JOURNAL_ID);
        closeOriginalAndOpenNextPeriod();

        PostingResult first = reversalService.reverse(reversalRequest(
            REVERSAL_COMMAND_ID,
            ORIGINAL_JOURNAL_ID,
            hash("main-reversal")));
        PostingResult replay = reversalService.reverse(reversalRequest(
            REVERSAL_COMMAND_ID,
            ORIGINAL_JOURNAL_ID,
            hash("main-reversal")));

        JournalSnapshot reversal = journalSnapshot(first.journalId());
        JournalSnapshot originalAfter = journalSnapshot(ORIGINAL_JOURNAL_ID);
        assertAll(
            () -> assertEquals(first, replay),
            () -> assertNotEquals(ORIGINAL_JOURNAL_ID, first.journalId()),
            () -> assertEquals(originalBefore, originalAfter),
            () -> assertEquals(NEXT_PERIOD_ID, reversal.periodId()),
            () -> assertEquals(REVERSAL_BOOKING_TIME, reversal.bookingTime()),
            () -> assertEquals(REVERSAL_VALUE_DATE, reversal.valueDate()),
            () -> assertEquals(ORIGINAL_JOURNAL_ID, reversal.reversalOfJournalId()),
            () -> assertEquals("REVERSAL", reversal.transactionType()),
            () -> assertEquals("Customer-requested correction", reversal.narration()),
            () -> assertEquals(TestPostingStack.uuid(212), reversal.correlationId()),
            () -> assertEquals(TestPostingStack.uuid(213), reversal.businessTransactionId()),
            () -> assertExactNegations(originalBefore.postings(), reversal.postings()),
            () -> assertEquals(2, count("funds.journal")),
            () -> assertEquals(4, count("funds.posting")),
            () -> assertEquals(2, count("funds.idempotency_command")),
            () -> assertEquals(2, count("funds.outbox_event")));
    }

    @Test
    void explicitReversalValueDateMustBelongToCurrentPeriod() throws SQLException {
        postingService.post(exampleA(ORIGINAL_COMMAND_ID, ORIGINAL_JOURNAL_ID));
        closeOriginalAndOpenNextPeriod();
        ReversalRequest outsideCurrentPeriod = canonical(new ReversalRequest(
            REVERSAL_COMMAND_ID,
            hash("outside-current-period"),
            ORIGINAL_JOURNAL_ID,
            TestPostingStack.uuid(212),
            TestPostingStack.uuid(213),
            NEXT_PERIOD_ID,
            REVERSAL_BOOKING_TIME,
            LocalDate.of(2026, 1, 31),
            "Explicit date outside current period"));

        assertThrows(InvalidJournalException.class, () -> reversalService.reverse(outsideCurrentPeriod));

        assertAll(
            () -> assertEquals(1, count("funds.journal")),
            () -> assertEquals(2, count("funds.posting")),
            () -> assertEquals(1, count("funds.outbox_event")));
    }

    @Test
    void ordinaryPostingToClosedPeriodRemainsRejected() throws SQLException {
        closeOriginalAndOpenNextPeriod();

        assertThrows(
            AccountingPeriodClosedException.class,
            () -> postingService.post(exampleA(ORIGINAL_COMMAND_ID, ORIGINAL_JOURNAL_ID)));

        assertAll(
            () -> assertEquals(0, count("funds.journal")),
            () -> assertEquals(0, count("funds.posting")),
            () -> assertEquals(0, count("funds.outbox_event")));
    }

    @Test
    void rejectsReversalOfReversalWithoutWritingAnything() throws SQLException {
        postingService.post(exampleA(ORIGINAL_COMMAND_ID, ORIGINAL_JOURNAL_ID));
        closeOriginalAndOpenNextPeriod();
        PostingResult firstReversal = reversalService.reverse(reversalRequest(
            REVERSAL_COMMAND_ID,
            ORIGINAL_JOURNAL_ID,
            hash("first-reversal")));
        DatabaseCounts before = databaseCounts();

        assertThrows(
            InvalidJournalException.class,
            () -> reversalService.reverse(canonical(new ReversalRequest(
                TestPostingStack.uuid(220),
                hash("reversal-chain"),
                firstReversal.journalId(),
                TestPostingStack.uuid(221),
                TestPostingStack.uuid(222),
                NEXT_PERIOD_ID,
                REVERSAL_BOOKING_TIME.plusSeconds(60),
                REVERSAL_VALUE_DATE,
                "Attempted reversal chain"))));

        assertEquals(before, databaseCounts());
    }

    @Test
    void longMinimumAmountIsRejectedBeforeItCanBecomeAnIrreversibleFact() throws SQLException {
        DatabaseCounts countsBefore = databaseCounts();

        assertThrows(
            IllegalArgumentException.class,
            () -> new PostingLine(TestPostingStack.uuid(214), TestPostingStack.PROVIDER_ASSET,
                NGN, Long.MIN_VALUE, 0, Map.of("case", "minimum")));

        assertAll(
            () -> assertEquals(countsBefore, databaseCounts()),
            () -> assertEquals(0, count("funds.journal")));
    }

    @Test
    void completedReversalPreflightWinsBeforeLaterPeriodPolicyChanges() throws SQLException {
        postingService.post(exampleA(ORIGINAL_COMMAND_ID, ORIGINAL_JOURNAL_ID));
        closeOriginalAndOpenNextPeriod();
        ReversalRequest request = reversalRequest(
            REVERSAL_COMMAND_ID, ORIGINAL_JOURNAL_ID, "completed-reversal-replay");
        PostingResult stored = reversalService.reverse(request);
        execute("UPDATE funds.accounting_period SET status = 'CLOSED' WHERE period_id = ?",
            NEXT_PERIOD_ID);
        DatabaseCounts before = databaseCounts();

        PostingResult replay = reversalService.reverse(request);

        assertAll(
            () -> assertEquals(stored, replay),
            () -> assertEquals(before, databaseCounts()));
    }

    @Test
    void usesCurrentBookPolicyForCorrectionInsteadOfHistoricalPolicy() throws SQLException {
        postingService.post(exampleA(ORIGINAL_COMMAND_ID, ORIGINAL_JOURNAL_ID));
        closeOriginalAndOpenNextPeriod();
        execute("UPDATE funds.book SET accounting_policy_version = 2 WHERE book_id = ?",
            TestPostingStack.BOOK_ID);

        PostingResult result = reversalService.reverse(reversalRequest(
            REVERSAL_COMMAND_ID,
            ORIGINAL_JOURNAL_ID,
            hash("current-policy")));

        assertAll(
            () -> assertEquals(2, queryLong(
                "SELECT policy_version FROM funds.journal WHERE journal_id = ?",
                result.journalId())),
            () -> assertEquals(2, queryLong(
                "SELECT accounting_policy_version FROM funds.book WHERE book_id = ?",
                TestPostingStack.BOOK_ID)));
    }

    @Test
    void completedCommandPreflightWinsBeforeInvalidOriginalLookup() throws SQLException {
        postingService.post(exampleA(ORIGINAL_COMMAND_ID, ORIGINAL_JOURNAL_ID));
        closeOriginalAndOpenNextPeriod();
        ReversalRequest completedRequest = reversalRequest(
            REVERSAL_COMMAND_ID,
            ORIGINAL_JOURNAL_ID,
            "preflight-result");
        PostingResult stored = reversalService.reverse(completedRequest);
        DatabaseCounts before = databaseCounts();

        PostingResult replay = reversalService.reverse(completedRequest);
        assertThrows(
            IdempotencyConflictException.class,
            () -> reversalService.reverse(reversalRequest(
                REVERSAL_COMMAND_ID,
                TestPostingStack.uuid(999_999),
                hash("different-preflight-result"))));
        assertThrows(
            IdempotencyConflictException.class,
            () -> reversalService.reverse(reversalRequest(
                REVERSAL_COMMAND_ID,
                stored.journalId(),
                hash("different-chain-result"))));

        assertAll(
            () -> assertEquals(stored, replay),
            () -> assertEquals(before, databaseCounts()));
    }

    @Test
    void staleReversalHashConflictsForEveryFinancialRequestFieldWithoutDatabaseWork()
        throws SQLException {
        ReversalRequest baseline = reversalRequest(
            REVERSAL_COMMAND_ID, ORIGINAL_JOURNAL_ID, "reversal-mutation-baseline");
        List<ReversalRequest> mutations = List.of(
            new ReversalRequest(TestPostingStack.uuid(301), baseline.requestHash(),
                baseline.originalJournalId(), baseline.correlationId(),
                baseline.businessTransactionId(), baseline.currentPeriodId(),
                baseline.bookingTime(), baseline.valueDate(), baseline.reason()),
            new ReversalRequest(baseline.commandId(), baseline.requestHash(),
                TestPostingStack.uuid(302), baseline.correlationId(),
                baseline.businessTransactionId(), baseline.currentPeriodId(),
                baseline.bookingTime(), baseline.valueDate(), baseline.reason()),
            new ReversalRequest(baseline.commandId(), baseline.requestHash(),
                baseline.originalJournalId(), TestPostingStack.uuid(303),
                baseline.businessTransactionId(), baseline.currentPeriodId(),
                baseline.bookingTime(), baseline.valueDate(), baseline.reason()),
            new ReversalRequest(baseline.commandId(), baseline.requestHash(),
                baseline.originalJournalId(), baseline.correlationId(),
                TestPostingStack.uuid(304), baseline.currentPeriodId(),
                baseline.bookingTime(), baseline.valueDate(), baseline.reason()),
            new ReversalRequest(baseline.commandId(), baseline.requestHash(),
                baseline.originalJournalId(), baseline.correlationId(),
                baseline.businessTransactionId(), TestPostingStack.uuid(305),
                baseline.bookingTime(), baseline.valueDate(), baseline.reason()),
            new ReversalRequest(baseline.commandId(), baseline.requestHash(),
                baseline.originalJournalId(), baseline.correlationId(),
                baseline.businessTransactionId(), baseline.currentPeriodId(),
                baseline.bookingTime().plusSeconds(1), baseline.valueDate(), baseline.reason()),
            new ReversalRequest(baseline.commandId(), baseline.requestHash(),
                baseline.originalJournalId(), baseline.correlationId(),
                baseline.businessTransactionId(), baseline.currentPeriodId(),
                baseline.bookingTime(), baseline.valueDate().plusDays(1), baseline.reason()),
            new ReversalRequest(baseline.commandId(), baseline.requestHash(),
                baseline.originalJournalId(), baseline.correlationId(),
                baseline.businessTransactionId(), baseline.currentPeriodId(),
                baseline.bookingTime(), baseline.valueDate(), "changed reversal reason"));
        DatabaseCounts before = databaseCounts();

        for (ReversalRequest mutation : mutations) {
            assertThrows(IdempotencyConflictException.class,
                () -> reversalService.reverse(mutation));
        }

        assertEquals(before, databaseCounts());
    }

    @Test
    void originalMustBelongToACompletedCommand() throws SQLException {
        insertIncompleteOriginal();
        closeOriginalAndOpenNextPeriod();

        assertThrows(
            InvalidJournalException.class,
            () -> reversalService.reverse(reversalRequest(
                REVERSAL_COMMAND_ID,
                ORIGINAL_JOURNAL_ID,
                hash("incomplete-original"))));

        assertEquals(0, queryLong(
            "SELECT count(*) FROM funds.idempotency_command WHERE command_id = ?",
            REVERSAL_COMMAND_ID));
    }

    @Test
    void rejectsJournalAbovePocPostingLimitWithoutWritingCorrection() throws SQLException {
        var postings = new ArrayList<PostingLine>();
        int pairs = ReversalService.MAX_POSTINGS_PER_JOURNAL / 2 + 1;
        for (int pair = 0; pair < pairs; pair++) {
            postings.add(new PostingLine(
                TestPostingStack.uuid(10_000L + pair * 2L),
                TestPostingStack.PROVIDER_ASSET,
                NGN,
                1,
                0,
                Map.of()));
            postings.add(new PostingLine(
                TestPostingStack.uuid(10_001L + pair * 2L),
                TestPostingStack.CUSTOMER_LIABILITY,
                NGN,
                -1,
                0,
                Map.of()));
        }
        DatabaseCounts before = databaseCounts();

        assertThrows(
            InvalidJournalException.class,
            () -> postingService.post(command(originalJournal(postings))));

        assertEquals(before, databaseCounts());
    }

    @Test
    void reversesAJournalAtTheExactTwoHundredFiftySixPostingBoundary()
        throws SQLException {
        var postings = new ArrayList<PostingLine>(ReversalService.MAX_POSTINGS_PER_JOURNAL);
        for (int pair = 0; pair < ReversalService.MAX_POSTINGS_PER_JOURNAL / 2; pair++) {
            postings.add(new PostingLine(
                TestPostingStack.uuid(20_000L + pair * 2L),
                TestPostingStack.PROVIDER_ASSET,
                NGN,
                1,
                0,
                Map.of()));
            postings.add(new PostingLine(
                TestPostingStack.uuid(20_001L + pair * 2L),
                TestPostingStack.CUSTOMER_LIABILITY,
                NGN,
                -1,
                0,
                Map.of()));
        }
        postingService.post(command(originalJournal(postings)));
        JournalSnapshot original = journalSnapshot(ORIGINAL_JOURNAL_ID);
        closeOriginalAndOpenNextPeriod();

        PostingResult result = reversalService.reverse(reversalRequest(
            REVERSAL_COMMAND_ID, ORIGINAL_JOURNAL_ID, hash("maximum-sized-reversal")));
        JournalSnapshot reversal = journalSnapshot(result.journalId());

        assertAll(
            () -> assertEquals(ReversalService.MAX_POSTINGS_PER_JOURNAL,
                original.postings().size()),
            () -> assertEquals(ReversalService.MAX_POSTINGS_PER_JOURNAL,
                reversal.postings().size()),
            () -> assertExactNegationMultiset(original.postings(), reversal.postings()),
            () -> assertEquals(2, count("funds.journal")),
            () -> assertEquals(512, count("funds.posting")));
    }

    @Test
    void rejectsPostingAbovePocDimensionLimitWithoutWritingCorrection() throws SQLException {
        var dimensions = new java.util.LinkedHashMap<String, String>();
        for (int index = 0; index <= ReversalService.MAX_DIMENSIONS_PER_POSTING; index++) {
            dimensions.put("dimension-" + index, "value-" + index);
        }
        DatabaseCounts before = databaseCounts();

        assertThrows(
            InvalidJournalException.class,
            () -> postingService.post(command(originalJournal(List.of(
                new PostingLine(TestPostingStack.uuid(204), TestPostingStack.PROVIDER_ASSET,
                    NGN, 1, 0, dimensions),
                new PostingLine(TestPostingStack.uuid(205), TestPostingStack.CUSTOMER_LIABILITY,
                    NGN, -1, 0, Map.of()))))));

        assertEquals(before, databaseCounts());
    }

    @Test
    void requestRequiresLowercaseSha256AndUtf8BoundedReason() throws SQLException {
        String exactBoundary = "é".repeat(256);
        postingService.post(exampleA(ORIGINAL_COMMAND_ID, ORIGINAL_JOURNAL_ID));
        closeOriginalAndOpenNextPeriod();
        ReversalRequest exactRequest = canonical(new ReversalRequest(
            REVERSAL_COMMAND_ID, hash("boundary"), ORIGINAL_JOURNAL_ID,
            TestPostingStack.uuid(212), TestPostingStack.uuid(213), NEXT_PERIOD_ID,
            REVERSAL_BOOKING_TIME, REVERSAL_VALUE_DATE, exactBoundary));
        PostingResult accepted = reversalService.reverse(exactRequest);

        assertAll(
            () -> assertEquals(512, queryLong(
                "SELECT octet_length(narration) FROM funds.journal WHERE journal_id = ?",
                accepted.journalId())),
            () -> assertThrows(IllegalArgumentException.class, () -> new ReversalRequest(
                REVERSAL_COMMAND_ID, "g".repeat(64), ORIGINAL_JOURNAL_ID,
                TestPostingStack.uuid(212), TestPostingStack.uuid(213), NEXT_PERIOD_ID,
                REVERSAL_BOOKING_TIME, REVERSAL_VALUE_DATE, "reason")),
            () -> assertThrows(IllegalArgumentException.class, () -> new ReversalRequest(
                REVERSAL_COMMAND_ID, hash("upper").toUpperCase(java.util.Locale.ROOT),
                ORIGINAL_JOURNAL_ID, TestPostingStack.uuid(212), TestPostingStack.uuid(213),
                NEXT_PERIOD_ID, REVERSAL_BOOKING_TIME, REVERSAL_VALUE_DATE, "reason")),
            () -> assertThrows(IllegalArgumentException.class, () -> new ReversalRequest(
                REVERSAL_COMMAND_ID, hash("too-long-reason"), ORIGINAL_JOURNAL_ID,
                TestPostingStack.uuid(212), TestPostingStack.uuid(213), NEXT_PERIOD_ID,
                REVERSAL_BOOKING_TIME, REVERSAL_VALUE_DATE, exactBoundary + "a")));
    }

    @Test
    void originalHeaderAndPostingsComeFromOneRepeatableReadSnapshot() throws Exception {
        postingService.post(exampleA(ORIGINAL_COMMAND_ID, ORIGINAL_JOURNAL_ID));
        closeOriginalAndOpenNextPeriod();
        var headerRead = new CountDownLatch(1);
        var appendCommitted = new CountDownLatch(1);
        var mutationFailure = new AtomicReference<Throwable>();
        DataSource interleaving = interleavingDataSource(dataSource, headerRead, appendCommitted);
        Thread mutator = Thread.startVirtualThread(() -> {
            try {
                assertTrue(headerRead.await(5, TimeUnit.SECONDS));
                appendBalancedLinesToOriginal();
            } catch (Throwable failure) {
                mutationFailure.set(failure);
            } finally {
                appendCommitted.countDown();
            }
        });

        PostingResult result = new ReversalService(interleaving, postingService).reverse(
            reversalRequest(REVERSAL_COMMAND_ID, ORIGINAL_JOURNAL_ID, hash("coherent-snapshot")));
        mutator.join(TimeUnit.SECONDS.toMillis(5));

        assertAll(
            () -> assertTrue(!mutator.isAlive(), "mutator must terminate"),
            () -> assertTrue(mutationFailure.get() instanceof SQLException),
            () -> assertEquals(2, queryLong(
                "SELECT count(*) FROM funds.posting WHERE journal_id = ?",
                ORIGINAL_JOURNAL_ID)),
            () -> assertEquals(2, journalSnapshot(result.journalId()).postings().size()),
            () -> assertEquals(
                List.of(-100_000L, 100_000L),
                journalSnapshot(result.journalId()).postings().stream()
                    .map(PostingSnapshot::signedMinorUnits).sorted().toList()));
    }

    @Test
    void everyReadHasFiniteTimeoutAndFailureRollsBackAndRestoresConnection() {
        var timeoutSet = new AtomicBoolean();
        var events = new ArrayList<String>();
        DataSource timingOut = timingOutDataSource(dataSource, timeoutSet, events);
        ReversalService service = new ReversalService(timingOut, postingService);

        assertThrows(
            LedgerPersistenceException.class,
            () -> service.reverse(reversalRequest(
                REVERSAL_COMMAND_ID,
                ORIGINAL_JOURNAL_ID,
                hash("query-timeout"))));

        assertAll(
            () -> assertTrue(timeoutSet.get()),
            () -> assertTrue(events.contains("autoCommit:false")),
            () -> assertTrue(events.contains("readOnly:true")),
            () -> assertTrue(events.contains("isolation:" + Connection.TRANSACTION_REPEATABLE_READ)),
            () -> assertTrue(events.contains("rollback")),
            () -> assertTrue(events.contains("readOnly:false")),
            () -> assertTrue(events.contains("autoCommit:true")),
            () -> assertEquals("close", events.getLast()));
    }

    @Test
    void everyReversalReadSetsTimeoutBeforeExecution() throws SQLException {
        postingService.post(exampleA(ORIGINAL_COMMAND_ID, ORIGINAL_JOURNAL_ID));
        closeOriginalAndOpenNextPeriod();
        var prepared = new java.util.concurrent.atomic.AtomicInteger();
        var executed = new java.util.concurrent.atomic.AtomicInteger();
        DataSource recording = timeoutRecordingDataSource(dataSource, prepared, executed);

        new ReversalService(recording, postingService).reverse(reversalRequest(
            REVERSAL_COMMAND_ID,
            ORIGINAL_JOURNAL_ID,
            hash("all-query-timeouts")));

        assertAll(
            () -> assertEquals(5, prepared.get()),
            () -> assertEquals(prepared.get(), executed.get()));
    }

    @Test
    void matchingInProgressCommandContinuesButDifferentHashConflictsFirst() throws SQLException {
        postingService.post(exampleA(ORIGINAL_COMMAND_ID, ORIGINAL_JOURNAL_ID));
        closeOriginalAndOpenNextPeriod();
        ReversalRequest request = reversalRequest(
            REVERSAL_COMMAND_ID, ORIGINAL_JOURNAL_ID, "visible-in-progress");
        execute("""
            INSERT INTO funds.idempotency_command (command_id, request_hash, state, created_at)
            VALUES (?, ?, 'IN_PROGRESS', CURRENT_TIMESTAMP)
            """, REVERSAL_COMMAND_ID, request.requestHash());

        assertThrows(
            IdempotencyConflictException.class,
            () -> reversalService.reverse(reversalRequest(
                REVERSAL_COMMAND_ID,
                TestPostingStack.uuid(999_999),
                hash("visible-in-progress-conflict"))));
        PostingResult result = reversalService.reverse(request);

        assertAll(
            () -> assertEquals(2, count("funds.journal")),
            () -> assertEquals(1, queryLong("""
                SELECT count(*) FROM funds.idempotency_command
                WHERE command_id = ? AND state = 'COMPLETED' AND journal_id = ?
                """, REVERSAL_COMMAND_ID, result.journalId())));
    }

    @Test
    void rejectsPostCompletionPostingAppendThatBreaksCanonicalFact() throws SQLException {
        postingService.post(exampleA(ORIGINAL_COMMAND_ID, ORIGINAL_JOURNAL_ID));
        DatabaseCounts before = databaseCounts();

        assertThrows(SQLException.class, this::appendBalancedLinesToOriginal);

        assertEquals(before, databaseCounts());
    }

    @Test
    void postingAndReversalHashesMatchPersistedMicrosecondBookingTimes() throws SQLException {
        Instant originalTime = Instant.parse("2026-01-15T10:00:00.123456Z");
        PostingCommand microsecondOriginal = withBookingTime(
            exampleA(ORIGINAL_COMMAND_ID, ORIGINAL_JOURNAL_ID),
            originalTime);
        PostingResult originalResult = postingService.post(microsecondOriginal);
        PostingCommand subMicrosecond = withBookingTime(
            exampleA(TestPostingStack.uuid(250), TestPostingStack.uuid(251)),
            Instant.parse("2026-01-15T10:00:00.123456001Z"));
        assertThrows(InvalidJournalException.class, () -> postingService.post(subMicrosecond));
        closeOriginalAndOpenNextPeriod();
        Instant reversalTime = Instant.parse("2026-02-15T09:30:00.654321Z");
        assertThrows(InvalidJournalException.class, () -> reversalService.reverse(canonical(new ReversalRequest(
            TestPostingStack.uuid(252),
            hash("sub-microsecond-reversal"),
            ORIGINAL_JOURNAL_ID,
            TestPostingStack.uuid(253),
            TestPostingStack.uuid(254),
            NEXT_PERIOD_ID,
            Instant.parse("2026-02-15T09:30:00.654321001Z"),
            REVERSAL_VALUE_DATE,
            "Invalid precision"))));
        ReversalRequest request = canonical(new ReversalRequest(
            REVERSAL_COMMAND_ID,
            hash("microsecond-reversal"),
            ORIGINAL_JOURNAL_ID,
            TestPostingStack.uuid(212),
            TestPostingStack.uuid(213),
            NEXT_PERIOD_ID,
            reversalTime,
            REVERSAL_VALUE_DATE,
            "Microsecond correction"));
        PostingResult reversalResult = reversalService.reverse(request);

        assertAll(
            () -> assertEquals(originalTime, journalSnapshot(ORIGINAL_JOURNAL_ID).bookingTime()),
            () -> assertEquals(originalResult.canonicalHash(), queryString(
                "SELECT canonical_hash FROM funds.journal WHERE journal_id = ?",
                ORIGINAL_JOURNAL_ID)),
            () -> assertEquals(reversalTime, journalSnapshot(reversalResult.journalId()).bookingTime()),
            () -> assertEquals(reversalResult.canonicalHash(), queryString(
                "SELECT canonical_hash FROM funds.journal WHERE journal_id = ?",
                reversalResult.journalId())));

    }

    @Test
    void acceptsDimensionJsonAtExactPocByteLimit() throws SQLException {
        postDimensionFixture(Map.of("k", "x".repeat(8_183)));
        assertEquals(ReversalService.MAX_DIMENSION_JSON_BYTES, queryLong("""
            SELECT octet_length(dimensions::text) FROM funds.posting WHERE posting_id = ?
            """, TestPostingStack.uuid(204)));
        closeOriginalAndOpenNextPeriod();

        PostingResult result = reversalService.reverse(reversalRequest(
            REVERSAL_COMMAND_ID,
            ORIGINAL_JOURNAL_ID,
            hash("exact-dimension-bytes")));

        assertEquals(2, journalSnapshot(result.journalId()).postings().size());
    }

    @Test
    void rejectsOversizedDimensionValueBeforeExpansion() throws SQLException {
        DatabaseCounts before = databaseCounts();

        assertThrows(InvalidJournalException.class,
            () -> postDimensionFixture(Map.of("k", "x".repeat(8_184))));

        assertEquals(before, databaseCounts());
    }

    @Test
    void rejectsOversizedDimensionKeyBeforeExpansion() throws SQLException {
        DatabaseCounts before = databaseCounts();

        assertThrows(InvalidJournalException.class,
            () -> postDimensionFixture(Map.of("k".repeat(8_185), "")));

        assertEquals(before, databaseCounts());
    }

    @Test
    void existingReversalIsRejectedBeforePostingAnotherCommand() throws SQLException {
        postingService.post(exampleA(ORIGINAL_COMMAND_ID, ORIGINAL_JOURNAL_ID));
        closeOriginalAndOpenNextPeriod();
        reversalService.reverse(reversalRequest(
            REVERSAL_COMMAND_ID,
            ORIGINAL_JOURNAL_ID,
            hash("first-correction")));
        DatabaseCounts before = databaseCounts();

        assertThrows(InvalidJournalException.class, () -> reversalService.reverse(reversalRequest(
            TestPostingStack.uuid(260),
            ORIGINAL_JOURNAL_ID,
            hash("second-correction"))));

        assertEquals(before, databaseCounts());
    }

    @Test
    void unrelatedDatabaseConstraintRemainsAPersistenceFailure() throws SQLException {
        postingService.post(exampleA(ORIGINAL_COMMAND_ID, ORIGINAL_JOURNAL_ID));
        UUID collidingPostingId = UUID.nameUUIDFromBytes(
            ("funds-reversal:" + REVERSAL_COMMAND_ID + ":posting:"
                + TestPostingStack.uuid(204)).getBytes(StandardCharsets.UTF_8));
        UUID collisionCommandId = TestPostingStack.uuid(280);
        postingService.post(command(new JournalDraft(
            TestPostingStack.uuid(281),
            collisionCommandId,
            TestPostingStack.uuid(282),
            TestPostingStack.uuid(283),
            TestPostingStack.LEGAL_ENTITY_ID,
            TestPostingStack.BOOK_ID,
            TestPostingStack.CHART_VERSION_ID,
            TestPostingStack.PERIOD_ID,
            "COLLISION_FIXTURE",
            "Pre-existing unrelated posting identifier",
            Instant.parse("2026-01-16T10:00:00Z"),
            LocalDate.of(2026, 1, 16),
            null,
            1,
            List.of(
                new PostingLine(collidingPostingId, TestPostingStack.PROVIDER_ASSET,
                    NGN, 1, 0, Map.of()),
                new PostingLine(TestPostingStack.uuid(284), TestPostingStack.CUSTOMER_LIABILITY,
                    NGN, -1, 0, Map.of())))));
        closeOriginalAndOpenNextPeriod();
        DatabaseCounts before = databaseCounts();

        assertThrows(LedgerPersistenceException.class, () -> reversalService.reverse(
            reversalRequest(REVERSAL_COMMAND_ID, ORIGINAL_JOURNAL_ID, hash("unrelated-constraint"))));

        assertEquals(before, databaseCounts());
    }

    @Test
    void concurrentDistinctCommandsCreateExactlyOneReversal() throws Exception {
        postingService.post(exampleA(ORIGINAL_COMMAND_ID, ORIGINAL_JOURNAL_ID));
        closeOriginalAndOpenNextPeriod();
        var bothCommandsComposed = new CyclicBarrier(2);
        PostingService racingPosting = new PostingService(
            dataSource,
            new JdbcLedgerRepository(),
            new PostgresRetryPolicy((commandId, attempt) -> {})) {
            @Override
            PostingResult postTrustedReversal(PostingCommand command) {
                try {
                    bothCommandsComposed.await(5, TimeUnit.SECONDS);
                } catch (Exception failure) {
                    throw new IllegalStateException("reversal race gate failed", failure);
                }
                return super.postTrustedReversal(command);
            }
        };
        ReversalService racingReversal = new ReversalService(dataSource, racingPosting);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<PostingResult> first = executor.submit(() -> racingReversal.reverse(reversalRequest(
            TestPostingStack.uuid(270), ORIGINAL_JOURNAL_ID, hash("race-one"))));
        Future<PostingResult> second = executor.submit(() -> racingReversal.reverse(reversalRequest(
            TestPostingStack.uuid(271), ORIGINAL_JOURNAL_ID, hash("race-two"))));

        List<Object> outcomes;
        try {
            outcomes = List.of(outcome(first), outcome(second));
        } finally {
            first.cancel(true);
            second.cancel(true);
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "race executor must terminate");
        }

        assertAll(
            () -> assertEquals(1, outcomes.stream().filter(PostingResult.class::isInstance).count()),
            () -> assertEquals(
                1,
                outcomes.stream().filter(InvalidJournalException.class::isInstance).count(),
                () -> "unexpected race outcomes: " + outcomes.stream()
                    .map(outcome -> outcome.getClass().getName() + ":" + outcome)
                    .toList()),
            () -> assertEquals(1, queryLong("""
                SELECT count(*) FROM funds.journal
                WHERE reversal_of_journal_id = ? AND transaction_type = 'REVERSAL'
                """, ORIGINAL_JOURNAL_ID)),
            () -> assertEquals(2, count("funds.journal")),
            () -> assertEquals(4, count("funds.posting")),
            () -> assertEquals(2, count("funds.idempotency_command")),
            () -> assertEquals(2, count("funds.outbox_event")));
    }

    private PostingCommand exampleA(UUID commandId, UUID journalId) {
        return command(new JournalDraft(
            journalId,
            commandId,
            TestPostingStack.uuid(202),
            TestPostingStack.uuid(203),
            TestPostingStack.LEGAL_ENTITY_ID,
            TestPostingStack.BOOK_ID,
            TestPostingStack.CHART_VERSION_ID,
            TestPostingStack.PERIOD_ID,
            "PROVIDER_INFLOW",
            "Example A provider inflow",
            Instant.parse("2026-01-15T10:00:00Z"),
            LocalDate.of(2026, 1, 15),
            null,
            1,
            List.of(
                new PostingLine(TestPostingStack.uuid(204), TestPostingStack.PROVIDER_ASSET,
                    NGN, 100_000, 0, Map.of("rail", "provider", "route", "nibss")),
                new PostingLine(TestPostingStack.uuid(205), TestPostingStack.CUSTOMER_LIABILITY,
                    NGN, -100_000, 0, Map.of("customer", "example-a")))));
    }

    private static PostingCommand withBookingTime(PostingCommand command, Instant bookingTime) {
        JournalDraft source = command.journal();
        var changed = new JournalDraft(
            source.journalId(),
            source.commandId(),
            source.correlationId(),
            source.businessTransactionId(),
            source.legalEntityId(),
            source.bookId(),
            source.chartVersionId(),
            source.periodId(),
            source.transactionType(),
            source.narration(),
            bookingTime,
            source.valueDate(),
            source.reversalOfJournalId(),
            source.policyVersion(),
            source.postings());
        return command(changed);
    }

    private void postDimensionFixture(Map<String, String> dimensions) {
        postingService.post(command(originalJournal(List.of(
            new PostingLine(TestPostingStack.uuid(204), TestPostingStack.PROVIDER_ASSET,
                NGN, 1, 0, dimensions),
            new PostingLine(TestPostingStack.uuid(205), TestPostingStack.CUSTOMER_LIABILITY,
                NGN, -1, 0, Map.of())))));
    }

    private static Object outcome(Future<PostingResult> future) throws Exception {
        try {
            return future.get(10, TimeUnit.SECONDS);
        } catch (ExecutionException failure) {
            return failure.getCause();
        }
    }

    private JournalDraft originalJournal(List<PostingLine> postings) {
        return new JournalDraft(
            ORIGINAL_JOURNAL_ID,
            ORIGINAL_COMMAND_ID,
            TestPostingStack.uuid(202),
            TestPostingStack.uuid(203),
            TestPostingStack.LEGAL_ENTITY_ID,
            TestPostingStack.BOOK_ID,
            TestPostingStack.CHART_VERSION_ID,
            TestPostingStack.PERIOD_ID,
            "LIMIT_FIXTURE",
            "POC bounded-reversal fixture",
            Instant.parse("2026-01-15T10:00:00Z"),
            LocalDate.of(2026, 1, 15),
            null,
            1,
            postings);
    }

    private static PostingCommand command(JournalDraft journal) {
        return new PostingCommand(
            journal.commandId(),
            new CanonicalCommandHasher().postingV2(journal),
            journal);
    }

    private static ReversalRequest reversalRequest(UUID commandId, UUID originalJournalId, String hashLabel) {
        return canonical(new ReversalRequest(
            commandId,
            hash(hashLabel),
            originalJournalId,
            TestPostingStack.uuid(212),
            TestPostingStack.uuid(213),
            NEXT_PERIOD_ID,
            REVERSAL_BOOKING_TIME,
            REVERSAL_VALUE_DATE,
            "Customer-requested correction"));
    }

    private static ReversalRequest canonical(ReversalRequest request) {
        String requestHash = new CanonicalCommandHasher().reversalV2(request);
        return new ReversalRequest(
            request.commandId(), requestHash, request.originalJournalId(), request.correlationId(),
            request.businessTransactionId(), request.currentPeriodId(), request.bookingTime(),
            request.valueDate(), request.reason());
    }

    private void closeOriginalAndOpenNextPeriod() throws SQLException {
        try (var connection = dataSource.getConnection()) {
            TestPostingStack.execute(connection, """
                UPDATE funds.accounting_period SET status = 'CLOSED' WHERE period_id = ?
                """, TestPostingStack.PERIOD_ID);
            TestPostingStack.execute(connection, """
                INSERT INTO funds.accounting_period
                    (period_id, book_id, business_date_from, business_date_to, status)
                VALUES (?, ?, DATE '2026-02-01', DATE '2026-02-28', 'OPEN')
                """, NEXT_PERIOD_ID, TestPostingStack.BOOK_ID);
        }
    }

    private void insertIncompleteOriginal() throws SQLException {
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                TestPostingStack.execute(connection, """
                    INSERT INTO funds.idempotency_command
                        (command_id, request_hash, state, created_at)
                    VALUES (?, ?, 'IN_PROGRESS', CURRENT_TIMESTAMP)
                    """, ORIGINAL_COMMAND_ID, hash("incomplete-command"));
                TestPostingStack.execute(connection, """
                    INSERT INTO funds.journal
                        (journal_id, command_id, correlation_id, business_transaction_id,
                         legal_entity_id, book_id, chart_version_id, period_id, transaction_type, narration,
                         booking_time, value_date, policy_version, canonical_hash)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'INCOMPLETE_FIXTURE', 'Incomplete command fixture',
                            TIMESTAMPTZ '2026-01-15 10:00:00+00', DATE '2026-01-15', 1, ?)
                    """, ORIGINAL_JOURNAL_ID, ORIGINAL_COMMAND_ID, TestPostingStack.uuid(202),
                    TestPostingStack.uuid(203), TestPostingStack.LEGAL_ENTITY_ID,
                    TestPostingStack.BOOK_ID, TestPostingStack.CHART_VERSION_ID,
                    TestPostingStack.PERIOD_ID, hash("incomplete-journal"));
                TestPostingStack.execute(connection, """
                    INSERT INTO funds.posting
                        (posting_id, journal_id, account_id, currency, signed_minor_units,
                         account_sequence, dimensions)
                    VALUES (?, ?, ?, 'NGN', 1, 1, '{}'::jsonb),
                           (?, ?, ?, 'NGN', -1, 1, '{}'::jsonb)
                    """, TestPostingStack.uuid(204), ORIGINAL_JOURNAL_ID,
                    TestPostingStack.PROVIDER_ASSET, TestPostingStack.uuid(205),
                    ORIGINAL_JOURNAL_ID, TestPostingStack.CUSTOMER_LIABILITY);
                connection.commit();
            } catch (Throwable failure) {
                connection.rollback();
                throw failure;
            }
        }
    }

    private void appendBalancedLinesToOriginal() throws SQLException {
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                TestPostingStack.execute(connection, """
                    INSERT INTO funds.posting
                        (posting_id, journal_id, account_id, currency, signed_minor_units,
                         account_sequence, dimensions)
                    VALUES (?, ?, ?, 'NGN', 7, 5, '{"mutation":"provider"}'::jsonb),
                           (?, ?, ?, 'NGN', -7, 7, '{"mutation":"customer"}'::jsonb)
                    """, TestPostingStack.uuid(230), ORIGINAL_JOURNAL_ID,
                    TestPostingStack.PROVIDER_ASSET, TestPostingStack.uuid(231),
                    ORIGINAL_JOURNAL_ID, TestPostingStack.CUSTOMER_LIABILITY);
                TestPostingStack.execute(connection, """
                    UPDATE funds.materialised_balance
                    SET signed_posting_total = signed_posting_total + 7,
                        latest_account_sequence = 5, version = version + 1
                    WHERE account_id = ?
                    """, TestPostingStack.PROVIDER_ASSET);
                TestPostingStack.execute(connection, """
                    UPDATE funds.materialised_balance
                    SET signed_posting_total = signed_posting_total - 7,
                        latest_account_sequence = 7, version = version + 1
                    WHERE account_id = ?
                    """, TestPostingStack.CUSTOMER_LIABILITY);
                TestPostingStack.execute(connection, """
                    UPDATE funds.control_account_projection
                    SET signed_posting_total = signed_posting_total + 7
                    WHERE book_id = ? AND control_account_code = ? AND currency = 'NGN'
                    """, TestPostingStack.BOOK_ID, TestPostingStack.PROVIDER_CONTROL);
                TestPostingStack.execute(connection, """
                    UPDATE funds.control_account_projection
                    SET signed_posting_total = signed_posting_total - 7
                    WHERE book_id = ? AND control_account_code = ? AND currency = 'NGN'
                    """, TestPostingStack.BOOK_ID, TestPostingStack.CUSTOMER_CONTROL);
                connection.commit();
            } catch (Throwable failure) {
                connection.rollback();
                throw failure;
            }
        }
    }

    private static DataSource interleavingDataSource(
        DataSource delegate,
        CountDownLatch headerRead,
        CountDownLatch appendCommitted
    ) {
        var triggered = new AtomicBoolean();
        return proxy(DataSource.class, delegate, (method, args) -> {
            if (!method.getName().equals("getConnection")) {
                return invoke(delegate, method, args);
            }
            Connection connection = (Connection) invoke(delegate, method, args);
            return proxy(Connection.class, connection, (connectionMethod, connectionArgs) -> {
                if (!connectionMethod.getName().equals("prepareStatement")) {
                    return invoke(connection, connectionMethod, connectionArgs);
                }
                String sql = (String) connectionArgs[0];
                PreparedStatement statement = (PreparedStatement) invoke(
                    connection,
                    connectionMethod,
                    connectionArgs);
                if (!sql.contains("FROM funds.journal")) {
                    return statement;
                }
                return proxy(PreparedStatement.class, statement, (statementMethod, statementArgs) -> {
                    Object value = invoke(statement, statementMethod, statementArgs);
                    if (!statementMethod.getName().equals("executeQuery") || !triggered.compareAndSet(false, true)) {
                        return value;
                    }
                    java.sql.ResultSet rows = (java.sql.ResultSet) value;
                    return proxy(java.sql.ResultSet.class, rows, (rowsMethod, rowsArgs) -> {
                        if (rowsMethod.getName().equals("close")) {
                            Object closed = invoke(rows, rowsMethod, rowsArgs);
                            headerRead.countDown();
                            if (!appendCommitted.await(5, TimeUnit.SECONDS)) {
                                throw new SQLException("timed out waiting for concurrent append");
                            }
                            return closed;
                        }
                        return invoke(rows, rowsMethod, rowsArgs);
                    });
                });
            });
        });
    }

    private static DataSource timingOutDataSource(
        DataSource delegate,
        AtomicBoolean timeoutSet,
        List<String> events
    ) {
        return proxy(DataSource.class, delegate, (method, args) -> {
            if (!method.getName().equals("getConnection")) {
                return invoke(delegate, method, args);
            }
            Connection connection = (Connection) invoke(delegate, method, args);
            return proxy(Connection.class, connection, (connectionMethod, connectionArgs) -> {
                switch (connectionMethod.getName()) {
                    case "setAutoCommit" -> events.add("autoCommit:" + connectionArgs[0]);
                    case "setReadOnly" -> events.add("readOnly:" + connectionArgs[0]);
                    case "setTransactionIsolation" -> events.add("isolation:" + connectionArgs[0]);
                    case "rollback", "commit", "close" -> events.add(connectionMethod.getName());
                    default -> { }
                }
                if (!connectionMethod.getName().equals("prepareStatement")) {
                    return invoke(connection, connectionMethod, connectionArgs);
                }
                PreparedStatement statement = (PreparedStatement) invoke(
                    connection,
                    connectionMethod,
                    connectionArgs);
                return proxy(PreparedStatement.class, statement, (statementMethod, statementArgs) -> {
                    if (statementMethod.getName().equals("setQueryTimeout")) {
                        timeoutSet.set((int) statementArgs[0] > 0);
                        return invoke(statement, statementMethod, statementArgs);
                    }
                    if (statementMethod.getName().equals("executeQuery")) {
                        if (!timeoutSet.get()) {
                            throw new AssertionError("query executed without a finite JDBC timeout");
                        }
                        throw new SQLTimeoutException("injected deterministic timeout", "57014");
                    }
                    return invoke(statement, statementMethod, statementArgs);
                });
            });
        });
    }

    private static DataSource timeoutRecordingDataSource(
        DataSource delegate,
        java.util.concurrent.atomic.AtomicInteger prepared,
        java.util.concurrent.atomic.AtomicInteger executed
    ) {
        return proxy(DataSource.class, delegate, (method, args) -> {
            if (!method.getName().equals("getConnection")) {
                return invoke(delegate, method, args);
            }
            Connection connection = (Connection) invoke(delegate, method, args);
            return proxy(Connection.class, connection, (connectionMethod, connectionArgs) -> {
                if (!connectionMethod.getName().equals("prepareStatement")) {
                    return invoke(connection, connectionMethod, connectionArgs);
                }
                String sql = (String) connectionArgs[0];
                PreparedStatement statement = (PreparedStatement) invoke(
                    connection,
                    connectionMethod,
                    connectionArgs);
                if (sql.contains("set_config('lock_timeout'")) {
                    return statement;
                }
                prepared.incrementAndGet();
                var timeoutSet = new AtomicBoolean();
                return proxy(PreparedStatement.class, statement, (statementMethod, statementArgs) -> {
                    if (statementMethod.getName().equals("setQueryTimeout")) {
                        timeoutSet.set((int) statementArgs[0] > 0);
                    }
                    if (statementMethod.getName().equals("executeQuery")) {
                        if (!timeoutSet.get()) {
                            throw new AssertionError("query executed without a finite JDBC timeout");
                        }
                        executed.incrementAndGet();
                    }
                    return invoke(statement, statementMethod, statementArgs);
                });
            });
        });
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, T delegate, ProxyCall call) {
        return (T) Proxy.newProxyInstance(
            type.getClassLoader(),
            new Class<?>[] {type},
            (ignored, method, args) -> call.invoke(method, args == null ? new Object[0] : args));
    }

    private static Object invoke(Object target, java.lang.reflect.Method method, Object[] args)
        throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        }
    }

    @FunctionalInterface
    private interface ProxyCall {
        Object invoke(java.lang.reflect.Method method, Object[] args) throws Throwable;
    }

    private static void assertExactNegations(List<PostingSnapshot> original, List<PostingSnapshot> reversal) {
        assertEquals(original.size(), reversal.size());
        for (int index = 0; index < original.size(); index++) {
            PostingSnapshot source = original.get(index);
            PostingSnapshot correction = reversal.get(index);
            assertAll(
                () -> assertNotEquals(source.postingId(), correction.postingId()),
                () -> assertNotEquals(source.journalId(), correction.journalId()),
                () -> assertEquals(source.accountId(), correction.accountId()),
                () -> assertEquals(source.currency(), correction.currency()),
                () -> assertEquals(Math.negateExact(source.signedMinorUnits()), correction.signedMinorUnits()),
                () -> assertEquals(source.dimensionsJson(), correction.dimensionsJson()),
                () -> assertEquals(source.accountSequence() + 1, correction.accountSequence()));
        }
    }

    private static void assertExactNegationMultiset(
        List<PostingSnapshot> original,
        List<PostingSnapshot> reversal
    ) {
        Map<ReversalFact, Long> expected = original.stream().collect(Collectors.groupingBy(
            posting -> new ReversalFact(
                posting.accountId(), posting.currency(),
                Math.negateExact(posting.signedMinorUnits()), posting.dimensionsJson()),
            Collectors.counting()));
        Map<ReversalFact, Long> actual = reversal.stream().collect(Collectors.groupingBy(
            posting -> new ReversalFact(
                posting.accountId(), posting.currency(),
                posting.signedMinorUnits(), posting.dimensionsJson()),
            Collectors.counting()));
        assertEquals(expected, actual);
    }

    private JournalSnapshot journalSnapshot(UUID journalId) throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                 SELECT journal_sequence, command_id, correlation_id, business_transaction_id, legal_entity_id,
                        book_id, period_id, transaction_type, narration, booking_time, value_date,
                        reversal_of_journal_id, policy_version, canonical_hash
                 FROM funds.journal
                 WHERE journal_id = ?
                 """)) {
            statement.setObject(1, journalId);
            try (var rows = statement.executeQuery()) {
                assertTrue(rows.next());
                return new JournalSnapshot(
                    journalId,
                    rows.getLong("journal_sequence"),
                    rows.getObject("command_id", UUID.class),
                    rows.getObject("correlation_id", UUID.class),
                    rows.getObject("business_transaction_id", UUID.class),
                    rows.getObject("legal_entity_id", UUID.class),
                    rows.getObject("book_id", UUID.class),
                    rows.getObject("period_id", UUID.class),
                    rows.getString("transaction_type"),
                    rows.getString("narration"),
                    rows.getObject("booking_time", OffsetDateTime.class).toInstant(),
                    rows.getObject("value_date", LocalDate.class),
                    rows.getObject("reversal_of_journal_id", UUID.class),
                    rows.getInt("policy_version"),
                    rows.getString("canonical_hash"),
                    postingSnapshots(connection, journalId));
            }
        }
    }

    private static List<PostingSnapshot> postingSnapshots(Connection connection, UUID journalId)
        throws SQLException {
        try (var statement = connection.prepareStatement("""
            SELECT posting_id, journal_id, account_id, currency, signed_minor_units, account_sequence,
                   dimensions::text AS dimensions_json
            FROM funds.posting
            WHERE journal_id = ?
            ORDER BY account_sequence, posting_id
            """)) {
            statement.setObject(1, journalId);
            try (var rows = statement.executeQuery()) {
                var postings = new ArrayList<PostingSnapshot>();
                while (rows.next()) {
                    postings.add(new PostingSnapshot(
                        rows.getObject("posting_id", UUID.class),
                        rows.getObject("journal_id", UUID.class),
                        rows.getObject("account_id", UUID.class),
                        rows.getString("currency"),
                        rows.getLong("signed_minor_units"),
                        rows.getLong("account_sequence"),
                        rows.getString("dimensions_json")));
                }
                return List.copyOf(postings);
            }
        }
    }

    private DatabaseCounts databaseCounts() throws SQLException {
        return new DatabaseCounts(
            count("funds.idempotency_command"),
            count("funds.journal"),
            count("funds.posting"),
            count("funds.outbox_event"));
    }

    private long count(String table) throws SQLException {
        return queryLong("SELECT count(*) FROM " + table);
    }

    private long queryLong(String sql, Object... values) throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++) {
                statement.setObject(index + 1, values[index]);
            }
            try (var rows = statement.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    private String queryString(String sql, Object... values) throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++) {
                statement.setObject(index + 1, values[index]);
            }
            try (var rows = statement.executeQuery()) {
                rows.next();
                return rows.getString(1);
            }
        }
    }

    private void execute(String sql, Object... values) throws SQLException {
        try (var connection = dataSource.getConnection()) {
            TestPostingStack.execute(connection, sql, values);
        }
    }

    private static String hash(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private record JournalSnapshot(
        UUID journalId,
        long journalSequence,
        UUID commandId,
        UUID correlationId,
        UUID businessTransactionId,
        UUID legalEntityId,
        UUID bookId,
        UUID periodId,
        String transactionType,
        String narration,
        Instant bookingTime,
        LocalDate valueDate,
        UUID reversalOfJournalId,
        int policyVersion,
        String canonicalHash,
        List<PostingSnapshot> postings) {}

    private record PostingSnapshot(
        UUID postingId,
        UUID journalId,
        UUID accountId,
        String currency,
        long signedMinorUnits,
        long accountSequence,
        String dimensionsJson) {}

    private record ReversalFact(
        UUID accountId,
        String currency,
        long signedMinorUnits,
        String dimensionsJson) {}

    private record DatabaseCounts(long commands, long journals, long postings, long outboxEvents) {}
}
