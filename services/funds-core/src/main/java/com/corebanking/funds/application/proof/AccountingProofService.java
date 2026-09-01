package com.corebanking.funds.application.proof;

import com.corebanking.funds.domain.CurrencyCode;
import com.corebanking.funds.infrastructure.postgres.JdbcAccountingProofRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Objects;
import java.util.UUID;

/**
 * Read-only accounting proofs (ACC-19). Both are sourced from funds.posting joined to
 * funds.journal up to a journal_sequence cutoff, never from materialised balances; the
 * control-account proof then compares that source against funds.control_account_projection so
 * a diverging projection is detected rather than trusted. This class only validates the
 * coordinates; the SQL lives in JdbcAccountingProofRepository.
 */
@ApplicationScoped
public class AccountingProofService {
    private final JdbcAccountingProofRepository repository;

    @Inject
    public AccountingProofService(JdbcAccountingProofRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    /**
     * Per-book, per-currency trial balance at a cutoff: debits and credits summed from the
     * signed postings; balanced iff the two totals are equal.
     */
    public TrialBalanceProof trialBalance(UUID bookId, CurrencyCode currency, long cutoff) {
        requireCoordinates(bookId, currency, cutoff);
        return repository.trialBalance(bookId, currency, cutoff);
    }

    /**
     * Control-account proof at a cutoff: the journal-sourced total for a control code, resolved
     * through the chart version each journal was posted under, against the materialised
     * projection. The repository rejects a cutoff behind later mapped activity, because the
     * projection only holds the current total.
     */
    public ControlAccountProof controlAccount(
        UUID bookId,
        String controlCode,
        CurrencyCode currency,
        long cutoff
    ) {
        requireCoordinates(bookId, currency, cutoff);
        ControlAccountProof.requireControlCode(controlCode);
        return repository.controlAccount(bookId, controlCode, currency, cutoff);
    }

    private static void requireCoordinates(UUID bookId, CurrencyCode currency, long cutoff) {
        Objects.requireNonNull(bookId, "bookId");
        Objects.requireNonNull(currency, "currency");
        if (cutoff < 0) {
            throw new IllegalArgumentException("cutoff must not be negative");
        }
    }
}
