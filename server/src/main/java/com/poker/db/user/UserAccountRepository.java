package com.poker.db.user;

import com.poker.auth.AuthProvider;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserAccountRepository extends JpaRepository<UserAccount, UUID> {

    Optional<UserAccount> findByProviderAndProviderSubject(
            AuthProvider provider,
            String providerSubject);
}
