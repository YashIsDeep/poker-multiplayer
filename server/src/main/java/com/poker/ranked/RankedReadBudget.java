package com.poker.ranked;

import java.util.function.LongSupplier;
import org.springframework.stereotype.Component;

/** One bounded process-wide bucket also limits random IDs/cache misses. */
@Component
public final class RankedReadBudget {
    static final int BURST = 200;
    static final int PER_SECOND = 100;
    private final LongSupplier nanoTime;
    private double available = BURST;
    private long last;

    public RankedReadBudget() { this(System::nanoTime); }
    RankedReadBudget(LongSupplier nanoTime) {
        this.nanoTime = nanoTime;
        last = nanoTime.getAsLong();
    }

    public synchronized boolean acquire() {
        long now = nanoTime.getAsLong();
        available = Math.min(BURST, available + Math.max(0, now - last) / 1_000_000_000d * PER_SECOND);
        last = now;
        if (available < 1) return false;
        available -= 1;
        return true;
    }
}
