package com.poker.auth;

import java.net.URI;
import java.net.URISyntaxException;

public record ExternalIdentity(
        AuthProvider provider,
        String providerSubject,
        String displayName,
        String avatarUrl,
        String email) {

    private static final int MAX_SUBJECT_CODE_POINTS = 255;
    private static final int MAX_AVATAR_LENGTH = 1024;
    private static final int MAX_EMAIL_LENGTH = 320;

    /** Providers that expose no verified email (Discord's identify scope, dev login). */
    public ExternalIdentity(
            AuthProvider provider, String providerSubject, String displayName, String avatarUrl) {
        this(provider, providerSubject, displayName, avatarUrl, null);
    }

    public ExternalIdentity {
        if (provider == null) {
            throw new IllegalArgumentException("provider is required");
        }
        validateSubject(providerSubject);
        displayName = DisplayNamePolicy.normalizeDevName(displayName);
        avatarUrl = sanitizeAvatarUrl(avatarUrl);
        email = normalizeEmail(email);
    }

    /**
     * Lower-cases and bounds a provider email so it can be compared against the
     * moderator allowlist. Anything malformed becomes null rather than throwing:
     * a missing email costs the user nothing but moderator standing.
     */
    public static String normalizeEmail(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim().toLowerCase(java.util.Locale.ROOT);
        if (trimmed.isEmpty() || trimmed.length() > MAX_EMAIL_LENGTH
                || DisplayNamePolicy.containsForbiddenCodePoint(trimmed)) {
            return null;
        }
        int at = trimmed.indexOf('@');
        if (at <= 0 || at != trimmed.lastIndexOf('@') || at == trimmed.length() - 1) {
            return null;
        }
        return trimmed;
    }

    public static String sanitizeAvatarUrl(String value) {
        if (value == null || value.length() > MAX_AVATAR_LENGTH
                || DisplayNamePolicy.containsForbiddenCodePoint(value)) {
            return null;
        }
        try {
            URI uri = new URI(value);
            if (!uri.isAbsolute()
                    || !"https".equalsIgnoreCase(uri.getScheme())
                    || uri.getHost() == null
                    || uri.getHost().isBlank()
                    || uri.getUserInfo() != null) {
                return null;
            }
            return value;
        } catch (URISyntaxException ex) {
            return null;
        }
    }

    private static void validateSubject(String subject) {
        if (subject == null || subject.isEmpty() || isBlank(subject)) {
            throw new IllegalArgumentException("provider subject is required");
        }
        if (subject.codePointCount(0, subject.length()) > MAX_SUBJECT_CODE_POINTS
                || DisplayNamePolicy.containsForbiddenCodePoint(subject)) {
            throw new IllegalArgumentException("provider subject is invalid");
        }
    }

    private static boolean isBlank(String value) {
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            if (!DisplayNamePolicy.isWhitespace(codePoint)) {
                return false;
            }
            offset += Character.charCount(codePoint);
        }
        return true;
    }
}
