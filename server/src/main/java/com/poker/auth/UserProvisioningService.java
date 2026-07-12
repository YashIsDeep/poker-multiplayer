package com.poker.auth;

import java.time.Clock;
import java.time.Instant;
import java.util.NoSuchElementException;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
public class UserProvisioningService {
    private static final String PROVIDER_SUBJECT_CONSTRAINT = "uk_app_user_provider_subject";

    private final UserProvisioningTransactions transactions;
    private final Clock clock;

    public UserProvisioningService(UserProvisioningTransactions transactions, Clock clock) {
        this.transactions = transactions;
        this.clock = clock;
    }

    public com.poker.db.user.UserAccount provision(ExternalIdentity identity) {
        Instant now = clock.instant();
        try {
            return transactions.upsertInNewTransaction(identity, now);
        } catch (DataIntegrityViolationException original) {
            if (!isProviderSubjectCollision(original)) {
                throw original;
            }
            try {
                return transactions.reloadAndRefreshInNewTransaction(identity, now);
            } catch (NoSuchElementException missingWinner) {
                throw original;
            }
        }
    }

    private static boolean isProviderSubjectCollision(DataIntegrityViolationException exception) {
        for (Throwable current = exception; current != null; current = current.getCause()) {
            if (current instanceof ConstraintViolationException violation
                    && PROVIDER_SUBJECT_CONSTRAINT.equalsIgnoreCase(violation.getConstraintName())) {
                return true;
            }
            String message = current.getMessage();
            if (message != null && message.toLowerCase(java.util.Locale.ROOT)
                    .contains(PROVIDER_SUBJECT_CONSTRAINT)) {
                return true;
            }
        }
        return false;
    }
}
