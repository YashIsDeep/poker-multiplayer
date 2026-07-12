package com.poker.room.application;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record PublicationReport(int attempted, List<String> failures, String correlationId) {
    public PublicationReport {
        if (attempted < 0) {
            throw new IllegalArgumentException("attempted cannot be negative");
        }
        Objects.requireNonNull(failures, "failures");
        failures = List.copyOf(failures);
        if (!failures.isEmpty()) {
            Objects.requireNonNull(correlationId, "correlationId");
        }
    }

    public static PublicationReport none() {
        return new PublicationReport(0, List.of(), null);
    }

    public static PublicationReport success(int attempted) {
        return new PublicationReport(attempted, List.of(), null);
    }

    public boolean hasFailures() {
        return !failures.isEmpty();
    }

    public int failed() {
        return failures.size();
    }

    public static String newCorrelationId() {
        return UUID.randomUUID().toString();
    }
}
