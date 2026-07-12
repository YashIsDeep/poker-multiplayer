package com.poker.auth;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.poker.db.user.UserAccount;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.dao.DataIntegrityViolationException;

class UserProvisioningServiceTest {
    private static final Instant NOW = Instant.parse("2026-07-12T08:00:00Z");
    private static final ExternalIdentity IDENTITY = new ExternalIdentity(
            AuthProvider.GOOGLE, "subject", "Alice", null);

    @Test
    void usesOneNewTransactionForAnOrdinaryLogin() {
        UserProvisioningTransactions transactions = Mockito.mock(UserProvisioningTransactions.class);
        UserAccount account = Mockito.mock(UserAccount.class);
        when(transactions.upsertInNewTransaction(IDENTITY, NOW)).thenReturn(account);

        UserAccount result = new UserProvisioningService(
                transactions, Clock.fixed(NOW, ZoneOffset.UTC)).provision(IDENTITY);

        assertSame(account, result);
        verify(transactions).upsertInNewTransaction(IDENTITY, NOW);
        verify(transactions, never()).reloadAndRefreshInNewTransaction(any(), any());
    }

    @Test
    void onlyNamedProviderSubjectCollisionsEnterOneRecoveryTransaction() {
        UserProvisioningTransactions transactions = Mockito.mock(UserProvisioningTransactions.class);
        UserAccount winner = Mockito.mock(UserAccount.class);
        DataIntegrityViolationException collision = new DataIntegrityViolationException(
                "duplicate key: uk_app_user_provider_subject", new RuntimeException("duplicate"));
        when(transactions.upsertInNewTransaction(IDENTITY, NOW)).thenThrow(collision);
        when(transactions.reloadAndRefreshInNewTransaction(IDENTITY, NOW)).thenReturn(winner);

        assertSame(winner, new UserProvisioningService(
                transactions, Clock.fixed(NOW, ZoneOffset.UTC)).provision(IDENTITY));
        verify(transactions).reloadAndRefreshInNewTransaction(IDENTITY, NOW);
    }

    @Test
    void unrelatedIntegrityFailuresAreNotRetried() {
        UserProvisioningTransactions transactions = Mockito.mock(UserProvisioningTransactions.class);
        DataIntegrityViolationException unrelated = new DataIntegrityViolationException("other constraint");
        when(transactions.upsertInNewTransaction(eq(IDENTITY), eq(NOW))).thenThrow(unrelated);

        assertSame(unrelated, assertThrows(DataIntegrityViolationException.class, () ->
                new UserProvisioningService(transactions, Clock.fixed(NOW, ZoneOffset.UTC))
                        .provision(IDENTITY)));
        verify(transactions, never()).reloadAndRefreshInNewTransaction(any(), any());
    }
}
