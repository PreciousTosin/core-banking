package com.corebanking.funds.application.proof;

import com.corebanking.funds.domain.CurrencyCode;
import com.corebanking.funds.infrastructure.postgres.JdbcAccountingProofRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Objects;
import java.util.UUID;

@ApplicationScoped
public class AccountingProofService {
    private final JdbcAccountingProofRepository repository;

    @Inject
    public AccountingProofService(JdbcAccountingProofRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    public TrialBalanceProof trialBalance(UUID bookId, CurrencyCode currency, long cutoff) {
        requireCoordinates(bookId, currency, cutoff);
        return repository.trialBalance(bookId, currency, cutoff);
    }

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
