package com.poker.auth;

import java.security.Principal;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/** Single authority on moderator standing; fail-closed for anything it cannot identify. */
@Component
public class ModeratorPolicy {
    private final Set<String> emails;
    private final boolean devUsersAreModerators;

    public ModeratorPolicy(ModerationProperties properties) {
        Objects.requireNonNull(properties, "properties");
        this.emails = properties.emails().stream()
                .map(ExternalIdentity::normalizeEmail)
                .filter(Objects::nonNull)
                .collect(Collectors.toUnmodifiableSet());
        this.devUsersAreModerators = properties.devUsersAreModerators();
    }

    public static ModeratorPolicy of(List<String> emails, boolean devUsersAreModerators) {
        return new ModeratorPolicy(new ModerationProperties(emails, devUsersAreModerators));
    }

    /** Grants nobody standing; the default wherever a policy is not wired in. */
    public static ModeratorPolicy denyAll() {
        return of(List.of(), false);
    }

    public boolean isModerator(CurrentUserPrincipal principal) {
        if (principal == null) {
            return false;
        }
        // The dev endpoint mints accounts with no provider email, so local and
        // end-to-end runs opt in explicitly rather than being locked out.
        if (devUsersAreModerators && principal.provider() == AuthProvider.DEV) {
            return true;
        }
        String email = ExternalIdentity.normalizeEmail(principal.email());
        return email != null && emails.contains(email);
    }

    /** Resolves the application principal out of a servlet or STOMP authentication. */
    public boolean isModerator(Principal principal) {
        if (principal instanceof CurrentUserPrincipal current) {
            return isModerator(current);
        }
        if (principal instanceof Authentication authentication
                && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof CurrentUserPrincipal current) {
            return isModerator(current);
        }
        return false;
    }
}
