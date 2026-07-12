package com.poker.ws;

public final class UnauthenticatedStompException extends RuntimeException {
    public UnauthenticatedStompException() {
        super("unauthenticated");
    }
}
