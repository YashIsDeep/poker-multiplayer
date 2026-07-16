package com.poker.analytics;

import com.poker.room.HandHistory;
import com.poker.room.RoomChange;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Watches committed room changes and archives each hand exactly once. Rooms
 * live only in memory, so the per-room high-water mark of archived hand ids
 * can live in memory too; concurrent publishes for one room serialize through
 * the map entry so a hand cannot be enqueued twice.
 */
@Component
public final class HandArchiveRecorder {
    private static final Logger log = LoggerFactory.getLogger(HandArchiveRecorder.class);

    private final HandArchiveWriter writer;
    private final ConcurrentHashMap<UUID, Long> archivedThrough = new ConcurrentHashMap<>();

    public HandArchiveRecorder(HandArchiveWriter writer) {
        this.writer = writer;
    }

    public void onChange(RoomChange change) {
        UUID roomId = change.roomId();
        if (roomId == null) return;
        if (change.room() == null) {
            // Genuine closures (empty LEFT, IDLE_CLOSED) carry the room's real
            // revision; a no-op change such as leaving a room the user is not
            // in carries revision 0 and must not clear a live room's mark.
            if (change.roomRevision() > 0) archivedThrough.remove(roomId);
            return;
        }
        List<HandHistory> completed = change.room().game().handHistories().stream()
                .filter(history -> history.completedAt() != null)
                .sorted(Comparator.comparingLong(HandHistory::handId))
                .toList();
        if (completed.isEmpty()) return;
        archivedThrough.compute(roomId, (ignored, last) -> {
            long floor = last == null ? 0 : last;
            for (HandHistory history : completed) {
                if (history.handId() <= floor) continue;
                try {
                    writer.append(HandArchiveMapper.map(change.room(), history));
                } catch (RuntimeException ex) {
                    log.error("Hand archive mapping failed roomId={} handId={}",
                            roomId, history.handId(), ex);
                }
                floor = history.handId();
            }
            return floor;
        });
    }
}
