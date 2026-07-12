package com.poker.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Locale;
import org.junit.jupiter.api.Test;

class DisplayNamePolicyTest {

    @Test
    void trimsAndCollapsesUnicodeWhitespaceAndNormalizesNfc() {
        assertEquals("Alice Béb", DisplayNamePolicy.normalizeDevName(" \u00a0Alice\u2000B\u0065\u0301b "));
    }

    @Test
    void rejectsControlsFormatsSeparatorsAndOverlongDevNames() {
        assertThrows(IllegalArgumentException.class, () -> DisplayNamePolicy.normalizeDevName("a\u0000b"));
        assertThrows(IllegalArgumentException.class, () -> DisplayNamePolicy.normalizeDevName("a\u200bb"));
        assertThrows(IllegalArgumentException.class, () -> DisplayNamePolicy.normalizeDevName("a\u2028b"));
        assertThrows(IllegalArgumentException.class,
                () -> DisplayNamePolicy.normalizeDevName("a".repeat(DisplayNamePolicy.MAX_CODE_POINTS + 1)));
    }

    @Test
    void providerNamesTruncateByCodePointAndInvalidNamesUseSafeFallback() {
        String emojiName = "😀".repeat(DisplayNamePolicy.MAX_CODE_POINTS + 1);
        String truncated = DisplayNamePolicy.normalizeProviderName(emojiName, "subject");
        assertEquals(DisplayNamePolicy.MAX_CODE_POINTS, truncated.codePointCount(0, truncated.length()));
        assertEquals("😀", truncated.substring(truncated.length() - 2));

        assertEquals("Player-a😀bcde", DisplayNamePolicy.normalizeProviderName("a\u200bb", "za😀bcde"));
    }

    @Test
    void fallbackSuffixIsCodePointSafeAndDevSubjectsUseRootLocale() {
        assertEquals("Player-a😀bcde", DisplayNamePolicy.fallbackName("za😀bcde"));
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertEquals("i", DisplayNamePolicy.devSubject("I"));
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    void invalidAvatarsAreDroppedWithoutChangingIdentity() {
        ExternalIdentity identity = new ExternalIdentity(
                AuthProvider.GOOGLE, "sub", "Alice", "http://example.com/avatar.png");
        assertNull(identity.avatarUrl());
        assertEquals("https://example.com/avatar.png",
                new ExternalIdentity(AuthProvider.GOOGLE, "sub", "Alice",
                        "https://example.com/avatar.png").avatarUrl());
    }
}
