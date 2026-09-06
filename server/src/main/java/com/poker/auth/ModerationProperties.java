package com.poker.auth;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Who may perform moderator-only actions. Emails are matched against the
 * verified provider email captured at sign-in, so an entry only grants standing
 * to whoever actually controls that mailbox at the identity provider.
 */
@ConfigurationProperties("poker.moderation")
public record ModerationProperties(List<String> emails, boolean devUsersAreModerators) {
    public ModerationProperties {
        // A stray blank entry in the YAML list must not take the context down.
        emails = emails == null
                ? List.of()
                : emails.stream().filter(java.util.Objects::nonNull).toList();
    }
}
