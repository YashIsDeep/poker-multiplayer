package com.poker.db.user;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.poker.auth.AuthProvider;
import com.poker.auth.ExternalIdentity;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.TestPropertySource;

@DataJpaTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:user_repository_test;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=validate"
})
class UserAccountRepositoryTest {
    private static final Instant NOW = Instant.parse("2026-07-12T08:00:00Z");

    @Autowired
    private UserAccountRepository repository;

    @Test
    void persistsGeneratedUuidAndLooksUpByOpaqueProviderKey() {
        UserAccount saved = repository.saveAndFlush(new UserAccount(
                new ExternalIdentity(AuthProvider.GOOGLE, "opaque-subject", "Alice", null), NOW));

        assertNotNull(saved.getId());
        UserAccount loaded = repository.findByProviderAndProviderSubject(
                AuthProvider.GOOGLE, "opaque-subject").orElseThrow();
        assertEquals(saved.getId(), loaded.getId());
        assertEquals("opaque-subject", loaded.getProviderSubject());
        assertEquals(NOW, loaded.getCreatedAt());
    }

    @Test
    void uniqueProviderSubjectsAreScopedToTheProvider() {
        repository.saveAndFlush(new UserAccount(
                new ExternalIdentity(AuthProvider.GOOGLE, "same-subject", "Google", null), NOW));
        repository.saveAndFlush(new UserAccount(
                new ExternalIdentity(AuthProvider.DISCORD, "same-subject", "Discord", null), NOW));

        assertThrows(DataIntegrityViolationException.class, () -> repository.saveAndFlush(new UserAccount(
                new ExternalIdentity(AuthProvider.GOOGLE, "same-subject", "Other", null), NOW)));
    }
}
