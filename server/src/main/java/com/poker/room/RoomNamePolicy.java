package com.poker.room;

import java.text.Normalizer;

/** Room-name normalization mirrors the safe T4 display-name rules. */
public final class RoomNamePolicy {
    public static final int MAX_CODE_POINTS = 40;

    private RoomNamePolicy() {
    }

    public static String normalize(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("room name is required");
        }
        String nfc = Normalizer.normalize(raw, Normalizer.Form.NFC);
        StringBuilder result = new StringBuilder(nfc.length());
        boolean pendingSpace = false;
        boolean hasContent = false;
        for (int offset = 0; offset < nfc.length();) {
            int codePoint = nfc.codePointAt(offset);
            if (forbidden(codePoint)) {
                throw new IllegalArgumentException("room name contains a forbidden character");
            }
            if (Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)) {
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
        int count = normalized.codePointCount(0, normalized.length());
        if (count == 0 || count > MAX_CODE_POINTS) {
            throw new IllegalArgumentException("room name length is invalid");
        }
        return normalized;
    }

    private static boolean forbidden(int codePoint) {
        int type = Character.getType(codePoint);
        return type == Character.CONTROL
                || type == Character.FORMAT
                || type == Character.LINE_SEPARATOR
                || type == Character.PARAGRAPH_SEPARATOR
                || type == Character.SURROGATE;
    }
}
