package com.corebanking.funds.infrastructure.postgres;

import com.corebanking.funds.application.proof.ControlAccountProof;
import com.corebanking.funds.application.proof.TrialBalanceProof;
import com.corebanking.funds.domain.CurrencyCode;
import com.corebanking.funds.domain.exception.LedgerPersistenceException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;

@ApplicationScoped
public class JdbcAccountingProofRepository {
    private static final int QUERY_TIMEOUT_SECONDS = 5;

    private final DataSource dataSource;

    @Inject
    public JdbcAccountingProofRepository(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    public TrialBalanceProof trialBalance(UUID bookId, CurrencyCode currency, long cutoff) {
        String sql = """
            SELECT
                coalesce(sum(CASE WHEN posting.signed_minor_units > 0
                    THEN posting.signed_minor_units::numeric ELSE 0::numeric END), 0::numeric) AS debits,
                coalesce(sum(CASE WHEN posting.signed_minor_units < 0
                    THEN -(posting.signed_minor_units::numeric) ELSE 0::numeric END), 0::numeric) AS credits
            FROM funds.posting posting
            JOIN funds.journal journal ON journal.journal_id = posting.journal_id
            WHERE journal.book_id = ?
              AND posting.currency = ?
              AND journal.journal_sequence <= ?
            """;
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(sql)) {
            bound(statement);
            statement.setObject(1, bookId);
            statement.setString(2, currency.value());
            statement.setLong(3, cutoff);
            try (var rows = statement.executeQuery()) {
                requireOneRow(rows);
                BigInteger debits = exactInteger(rows, "debits");
                BigInteger credits = exactInteger(rows, "credits");
                return new TrialBalanceProof(bookId, currency, cutoff, debits, credits, debits.equals(credits));
            }
        } catch (SQLException failure) {
            throw new LedgerPersistenceException(failure);
        }
    }

    public ControlAccountProof controlAccount(
        UUID bookId,
        String controlCode,
        CurrencyCode currency,
        long cutoff
    ) {
        String sql = """
            WITH source AS (
                SELECT coalesce(sum(posting.signed_minor_units::numeric), 0::numeric) AS source_total,
                       coalesce(max(journal.journal_sequence), 0::bigint) AS source_latest_journal_sequence
                FROM funds.posting posting
                JOIN funds.journal journal ON journal.journal_id = posting.journal_id
                JOIN funds.ledger_account account ON account.account_id = posting.account_id
                WHERE journal.book_id = ?
                  AND account.book_id = journal.book_id
                  AND account.control_account_code = ?
                  AND posting.currency = ?
                  AND account.currency = posting.currency
                  AND journal.journal_sequence <= ?
            ), projection AS (
                SELECT signed_posting_total::numeric AS projection_total, latest_journal_sequence
                FROM funds.control_account_projection
                WHERE book_id = ? AND control_account_code = ? AND currency = ?
            )
            SELECT source.source_total,
                   source.source_latest_journal_sequence,
                   coalesce(projection.projection_total, 0::numeric) AS projection_total,
                   projection.latest_journal_sequence
            FROM source
            LEFT JOIN projection ON true
            """;
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(sql)) {
            bound(statement);
            statement.setObject(1, bookId);
            statement.setString(2, controlCode);
            statement.setString(3, currency.value());
            statement.setLong(4, cutoff);
            statement.setObject(5, bookId);
            statement.setString(6, controlCode);
            statement.setString(7, currency.value());
            try (var rows = statement.executeQuery()) {
                requireOneRow(rows);
                long sourceSequence = rows.getLong("source_latest_journal_sequence");
                long projectionSequence = rows.getLong("latest_journal_sequence");
                boolean projectionMissing = rows.wasNull();
                if (projectionMissing && sourceSequence != 0) {
                    throw new IllegalStateException(
                        "control projection is missing for mapped source at sequence " + sourceSequence);
                }
                if (!projectionMissing && projectionSequence != sourceSequence) {
                    throw new IllegalStateException(
                        "control projection latest journal sequence " + projectionSequence
                            + " does not match source latest journal sequence " + sourceSequence
                            + " at cutoff " + cutoff);
                }
                BigInteger source = exactInteger(rows, "source_total");
                BigInteger projection = exactInteger(rows, "projection_total");
                return new ControlAccountProof(
                    controlCode, currency, cutoff, source, projection, source.subtract(projection));
            }
        } catch (SQLException failure) {
            throw new LedgerPersistenceException(failure);
        }
    }

    private static void bound(java.sql.PreparedStatement statement) throws SQLException {
        statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
        statement.setFetchSize(1);
        statement.setMaxRows(1);
    }

    private static void requireOneRow(ResultSet rows) throws SQLException {
        if (!rows.next()) {
            throw new SQLException("accounting proof aggregate returned no row");
        }
    }

    private static BigInteger exactInteger(ResultSet rows, String column) throws SQLException {
        BigDecimal value = rows.getBigDecimal(column);
        if (value == null) {
            throw new SQLException("accounting proof returned null " + column);
        }
        try {
            return value.toBigIntegerExact();
        } catch (ArithmeticException nonIntegral) {
            throw new SQLException("accounting proof returned non-integral " + column, nonIntegral);
        }
    }
}
