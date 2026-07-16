package com.poker.presence;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Atomic bidirectional index of authenticated STOMP sessions. */
public final class UserSocketRegistry {
    public enum Transition { ZERO_TO_ONE, ONE_TO_ZERO, UNCHANGED }

    private final Map<UUID, Set<String>> byUser = new HashMap<>();
    private final Map<String, UUID> bySession = new HashMap<>();
    private final Map<String, String> httpSessionBySocket = new HashMap<>();
    private final Map<String, Set<String>> byHttpSession = new HashMap<>();

    public synchronized Transition register(UUID userId, String sessionId) {
        return register(userId, sessionId, null);
    }

    public synchronized Transition register(UUID userId, String sessionId, String httpSessionId) {
        Objects.requireNonNull(userId, "userId");
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId is required");
        }
        UUID existingUser = bySession.get(sessionId);
        if (existingUser != null && !existingUser.equals(userId)) {
            throw new IllegalStateException("session identity changed");
        }
        if (existingUser != null) return Transition.UNCHANGED;

        Set<String> sessions = byUser.computeIfAbsent(userId, ignored -> new HashSet<>());
        boolean first = sessions.isEmpty();
        sessions.add(sessionId);
        bySession.put(sessionId, userId);
        if (httpSessionId != null && !httpSessionId.isBlank()) {
            httpSessionBySocket.put(sessionId, httpSessionId);
            byHttpSession.computeIfAbsent(httpSessionId, ignored -> new HashSet<>()).add(sessionId);
        }
        return first ? Transition.ZERO_TO_ONE : Transition.UNCHANGED;
    }

    public synchronized Removed remove(String sessionId) {
        if (sessionId == null) return new Removed(null, Transition.UNCHANGED);
        UUID userId = bySession.remove(sessionId);
        if (userId == null) return new Removed(null, Transition.UNCHANGED);
        String httpSessionId = httpSessionBySocket.remove(sessionId);
        if (httpSessionId != null) {
            Set<String> sockets = byHttpSession.get(httpSessionId);
            if (sockets != null) {
                sockets.remove(sessionId);
                if (sockets.isEmpty()) byHttpSession.remove(httpSessionId);
            }
        }
        Set<String> sessions = byUser.get(userId);
        if (sessions == null || !sessions.remove(sessionId)) {
            throw new IllegalStateException("socket indexes diverged");
        }
        if (sessions.isEmpty()) {
            byUser.remove(userId);
            return new Removed(userId, Transition.ONE_TO_ZERO);
        }
        return new Removed(userId, Transition.UNCHANGED);
    }

    public synchronized int count(UUID userId) {
        return byUser.getOrDefault(userId, Set.of()).size();
    }

    public synchronized int total() {
        return bySession.size();
    }

    /** Immutable per-user socket counts for read-only diagnostics. */
    public synchronized Map<UUID, Integer> socketCountsByUser() {
        Map<UUID, Integer> counts = new HashMap<>();
        byUser.forEach((userId, sessions) -> counts.put(userId, sessions.size()));
        return Map.copyOf(counts);
    }

    /** Removes every STOMP socket backed by an explicitly invalidated HTTP session. */
    public synchronized Set<UUID> removeHttpSession(String httpSessionId) {
        if (httpSessionId == null) return Set.of();
        Set<String> sessions = Set.copyOf(byHttpSession.getOrDefault(httpSessionId, Set.of()));
        Set<UUID> becameSocketless = new HashSet<>();
        for (String sessionId : sessions) {
            Removed removed = remove(sessionId);
            if (removed.userId() != null && removed.transition() == Transition.ONE_TO_ZERO) {
                becameSocketless.add(removed.userId());
            }
        }
        return Set.copyOf(becameSocketless);
    }

    public record Removed(UUID userId, Transition transition) {}
}
