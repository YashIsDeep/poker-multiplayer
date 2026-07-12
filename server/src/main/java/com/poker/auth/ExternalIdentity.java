package com.poker.auth;

import java.net.URI;
import java.net.URISyntaxException;

public record ExternalIdentity(
        AuthProvider provider,
        String providerSubject,
        String displayName,
        String avatarUrl) {

    private static final int MAX_SUBJECT_CODE_POINTS = 255;
    private static final int MAX_AVATAR_LENGTH = 1024;

    public ExternalIdentity {
        if (provider == null) {
            throw new IllegalArgumentException("provider is required");
        }
        validateSubject(providerSubject);
        displayName = DisplayNamePolicy.normalizeDevName(displayName);
        avatarUrl = sanitizeAvatarUrl(avatarUrl);
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
