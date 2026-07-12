package com.poker.auth;

import com.poker.db.user.UserAccount;
import com.poker.db.user.UserAccountRepository;
import java.time.Instant;
import java.util.NoSuchElementException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserProvisioningTransactions {
    private final UserAccountRepository repository;

    public UserProvisioningTransactions(UserAccountRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UserAccount upsertInNewTransaction(ExternalIdentity identity, Instant now) {
        UserAccount account = repository.findByProviderAndProviderSubject(
                        identity.provider(), identity.providerSubject())
                .orElseGet(() -> new UserAccount(identity, now));
        if (account.getId() != null) {
            account.refreshFrom(identity, now);
        }
        return repository.saveAndFlush(account);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UserAccount reloadAndRefreshInNewTransaction(ExternalIdentity identity, Instant now) {
        UserAccount account = repository.findByProviderAndProviderSubject(
                        identity.provider(), identity.providerSubject())
                .orElseThrow(() -> new NoSuchElementException("provider identity winner is not visible"));
        account.refreshFrom(identity, now);
        return repository.saveAndFlush(account);
    }
}
