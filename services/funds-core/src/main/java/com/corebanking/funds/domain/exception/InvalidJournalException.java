package com.corebanking.funds.domain.exception;

public class InvalidJournalException extends RuntimeException {
    public InvalidJournalException(String message) {
        super(message);
    }
}
