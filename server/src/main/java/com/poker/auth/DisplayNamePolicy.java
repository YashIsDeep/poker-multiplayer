package com.poker.auth;

import java.text.Normalizer;
import java.util.Locale;

public final class DisplayNamePolicy {
    public static final int MAX_CODE_POINTS = 20;
    private static final int FALLBACK_SUFFIX_CODE_POINTS = 6;

    private DisplayNamePolicy() {
    }

    public static String normalizeDevName(String raw) {
        String normalized = canonicalize(raw);
        requireLength(normalized);
        return normalized;
    }

    public static String normalizeProviderName(String raw, String providerSubject) {
        String fallback = fallbackName(providerSubject);
        try {
            String normalized = canonicalize(raw);
            if (normalized.isEmpty()) {
                return fallback;
            }
            return truncateToCodePoints(normalized, MAX_CODE_POINTS);
        } catch (IllegalArgumentException ex) {
            return fallback;
        }
    }

    public static String devSubject(String normalizedDisplayName) {
        if (normalizedDisplayName == null) {
            throw new IllegalArgumentException("display name is required");
        }
        return Normalizer.normalize(
                normalizedDisplayName.toLowerCase(Locale.ROOT), Normalizer.Form.NFC);
    }

    public static String fallbackName(String providerSubject) {
        if (providerSubject == null || providerSubject.isEmpty() || containsForbiddenCodePoint(providerSubject)) {
            throw new IllegalArgumentException("provider subject is required");
        }
        int count = providerSubject.codePointCount(0, providerSubject.length());
        int start = providerSubject.offsetByCodePoints(0, Math.max(0, count - FALLBACK_SUFFIX_CODE_POINTS));
        return "Player-" + providerSubject.substring(start);
    }

    public static boolean isWhitespace(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }

    public static boolean containsForbiddenCodePoint(String value) {
        if (value == null) {
            return true;
        }
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            if (isForbidden(codePoint)) {
                return true;
            }
            offset += Character.charCount(codePoint);
        }
        return false;
    }

    static boolean hasUsableProviderContent(String raw) {
        try {
            canonicalize(raw);
            return true;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private static String canonicalize(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("display name is required");
        }
        String nfc = Normalizer.normalize(raw, Normalizer.Form.NFC);
        StringBuilder result = new StringBuilder(nfc.length());
        boolean pendingSpace = false;
        boolean hasContent = false;

        for (int offset = 0; offset < nfc.length();) {
            int codePoint = nfc.codePointAt(offset);
            if (isForbidden(codePoint)) {
                throw new IllegalArgumentException("display name contains a forbidden character");
            }
            if (isWhitespace(codePoint)) {
                if (hasContent) {
                    pendingSpace = true;
                }
            } else {
                if (pendingSpace) {
                    result.append(' ');
                    pendingSpace = false;
                }
                result.appendCodePoint(codePoint);
                hasContent = true;
            }
            offset += Character.charCount(codePoint);
        }

        String normalized = Normalizer.normalize(result, Normalizer.Form.NFC);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("display name must not be blank");
        }
        if (containsForbiddenCodePoint(normalized)) {
            throw new IllegalArgumentException("display name contains a forbidden character");
        }
        return normalized;
    }

    private static String truncateToCodePoints(String value, int maxCodePoints) {
        int count = value.codePointCount(0, value.length());
        if (count <= maxCodePoints) {
            return value;
        }
        return value.substring(0, value.offsetByCodePoints(0, maxCodePoints));
    }

    private static void requireLength(String value) {
        if (value.codePointCount(0, value.length()) > MAX_CODE_POINTS) {
            throw new IllegalArgumentException("display name is too long");
        }
    }

    private static boolean isForbidden(int codePoint) {
        int type = Character.getType(codePoint);
        return type == Character.CONTROL
                || type == Character.FORMAT
                || type == Character.LINE_SEPARATOR
                || type == Character.PARAGRAPH_SEPARATOR
                || type == Character.SURROGATE;
    }
}
