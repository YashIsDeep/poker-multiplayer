package com.poker.auth;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

class ModeratorPolicyTest {
    private static final String ALLOWED = "yashparth.gupta@gmail.com";

    @Test
    void grantsStandingOnlyToAnAllowListedEmailRegardlessOfCasingOrPadding() {
        ModeratorPolicy policy = ModeratorPolicy.of(List.of("  YashParth.Gupta@Gmail.com "), false);

        assertTrue(policy.isModerator(google(ALLOWED)));
        assertTrue(policy.isModerator(google("  YASHPARTH.GUPTA@GMAIL.COM  ")));
        assertFalse(policy.isModerator(google("someone.else@gmail.com")));
        assertFalse(policy.isModerator(google(null)));
    }

    @Test
    void devAccountsCarryNoEmailAndOnlyCountWhenTheProfileOptsIn() {
        SessionUserPrincipal dev = new SessionUserPrincipal(
                UUID.randomUUID(), AuthProvider.DEV, "Alice", null);

        assertFalse(ModeratorPolicy.of(List.of(ALLOWED), false).isModerator(dev));
        assertTrue(ModeratorPolicy.of(List.of(ALLOWED), true).isModerator(dev));
        // The opt-in covers dev logins only; a real provider still needs the email.
        assertFalse(ModeratorPolicy.of(List.of(ALLOWED), true).isModerator(google("other@gmail.com")));
    }

    @Test
    void failsClosedForNullEmptyAndForeignPrincipals() {
        ModeratorPolicy policy = ModeratorPolicy.of(List.of(ALLOWED), true);

        assertFalse(policy.isModerator((CurrentUserPrincipal) null));
        assertFalse(policy.isModerator((java.security.Principal) null));
        assertFalse(policy.isModerator(
                UsernamePasswordAuthenticationToken.authenticated("provider-claim", null, List.of())));
        assertFalse(policy.isModerator(
                UsernamePasswordAuthenticationToken.unauthenticated(google(ALLOWED), null)));
    }

    @Test
    void readsTheApplicationPrincipalOutOfAnAuthentication() {
        ModeratorPolicy policy = ModeratorPolicy.of(List.of(ALLOWED), false);
        SessionUserPrincipal principal = google(ALLOWED);

        assertTrue(policy.isModerator(UsernamePasswordAuthenticationToken.authenticated(
                principal, null, principal.authorities())));
    }

    @Test
    void ignoresMalformedAllowListEntriesInsteadOfMatchingThem() {
        ModeratorPolicy policy = ModeratorPolicy.of(
                java.util.Arrays.asList("not-an-email", "", null, ALLOWED), false);

        assertFalse(policy.isModerator(google("not-an-email")));
        assertTrue(policy.isModerator(google(ALLOWED)));
    }

    private static SessionUserPrincipal google(String email) {
        return new SessionUserPrincipal(
                UUID.randomUUID(), AuthProvider.GOOGLE, "Alice", null, email);
    }
}
