package com.poker.ws.protocol;

import com.fasterxml.jackson.annotation.JsonAnySetter;

/** Rejects undeclared wire fields even if another Jackson mapper is selected accidentally. */
public interface StrictProtocolObject {
    @JsonAnySetter
    default void rejectUnknownField(String field, Object ignoredValue) {
        throw new IllegalArgumentException("unknown protocol field: " + field);
    }
}
