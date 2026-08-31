package com.corebanking.funds.infrastructure.postgres;

import com.corebanking.funds.application.PostingCommand;
import com.corebanking.funds.application.PostingResult;
import java.sql.Connection;
import java.util.Optional;
import java.util.UUID;

public interface LedgerRepository {
    PostingResult post(Connection connection, PostingCommand command);

    Optional<PostingResult> findCompleted(Connection connection, UUID commandId, String requestHash);
}
