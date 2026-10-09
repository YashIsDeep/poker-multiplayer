package com.poker.ranked;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class RankedReadBudgetTest {
    @Test void randomIdentitiesAndForwardingHeadersCannotCreateExtraBuckets() {
        var clock = new AtomicLong();
        var budget = new RankedReadBudget(clock::get);
        for (int i = 0; i < RankedReadBudget.BURST; i++) assertThat(budget.acquire()).isTrue();
        for (int i = 0; i < 10_000; i++) assertThat(budget.acquire()).isFalse();
        clock.addAndGet(1_000_000_000);
        for (int i = 0; i < RankedReadBudget.PER_SECOND; i++) assertThat(budget.acquire()).isTrue();
        assertThat(budget.acquire()).isFalse();
        clock.addAndGet(100_000_000_000L);
        for (int i = 0; i < RankedReadBudget.BURST; i++) assertThat(budget.acquire()).isTrue();
        assertThat(budget.acquire()).isFalse();
    }
}
