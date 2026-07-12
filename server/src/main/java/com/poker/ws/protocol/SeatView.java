package com.poker.ws.protocol;

import java.util.UUID;

public record SeatView(int seat, UUID userId) {
}
