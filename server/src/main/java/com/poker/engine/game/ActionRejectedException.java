package com.poker.engine.game;

import java.util.Objects;

public final class ActionRejectedException extends IllegalArgumentException {
    private final ActionRejectionReason reason;

    public ActionRejectedException(ActionRejectionReason reason) {
        super(Objects.requireNonNull(reason, "reason").name());
        this.reason = reason;
    }

    public ActionRejectionReason reason() {
        return reason;
    }
}
