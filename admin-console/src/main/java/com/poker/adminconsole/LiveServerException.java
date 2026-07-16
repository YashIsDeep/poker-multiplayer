package com.poker.adminconsole;

/** A live-server command failed in an expected, user-explainable way. */
final class LiveServerException extends RuntimeException {
    LiveServerException(String message) {
        super(message);
    }

    LiveServerException(String message, Throwable cause) {
        super(message, cause);
    }
}
