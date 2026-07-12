package com.poker.engine.game;

import java.util.List;

final class SeatOrder {
    private SeatOrder() {}

    static int nextOccupied(List<Integer> occupiedSeats, int fromSeat) {
        for (int seat : occupiedSeats) {
            if (seat > fromSeat) return seat;
        }
        return occupiedSeats.get(0);
    }

    static List<Integer> circularFrom(List<Integer> occupiedSeats, int startSeat) {
        int start = occupiedSeats.indexOf(startSeat);
        if (start < 0) throw new IllegalArgumentException("startSeat is not occupied");
        java.util.ArrayList<Integer> ordered = new java.util.ArrayList<>(occupiedSeats.size());
        for (int i = 0; i < occupiedSeats.size(); i++) {
            ordered.add(occupiedSeats.get((start + i) % occupiedSeats.size()));
        }
        return List.copyOf(ordered);
    }
}
