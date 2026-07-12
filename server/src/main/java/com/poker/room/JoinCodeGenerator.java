package com.poker.room;

import java.security.SecureRandom;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;

/** Secure in production and deterministic when a test generator is injected. */
public final class JoinCodeGenerator {
    private final RandomGenerator random;
    private final Supplier<JoinCode> supplier;

    public JoinCodeGenerator() {
        this(new SecureRandom());
    }

    public JoinCodeGenerator(RandomGenerator random) {
        this.random = Objects.requireNonNull(random, "random");
        this.supplier = null;
    }

    public JoinCodeGenerator(Supplier<JoinCode> supplier) {
        this.random = null;
        this.supplier = Objects.requireNonNull(supplier, "supplier");
    }

    public JoinCode next() {
        if (supplier != null) {
            return Objects.requireNonNull(supplier.get(), "generated join code");
        }
        StringBuilder value = new StringBuilder(JoinCode.LENGTH);
        for (int i = 0; i < JoinCode.LENGTH; i++) {
            value.append(JoinCode.ALPHABET.charAt(random.nextInt(JoinCode.ALPHABET.length())));
        }
        return new JoinCode(value.toString());
    }

    public JoinCode generate() {
        return next();
    }
}
