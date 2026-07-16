package com.poker.analytics;

import com.poker.room.RoomChange;
import com.poker.room.application.PublicationReport;
import com.poker.room.application.RoomEventSink;
import com.poker.ws.StompRoomEventSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * Every committed change flows through the event sink, so decorating it is
 * the single choke point where completed hands can be archived regardless of
 * whether the change came from a player command, a timeout, or presentation
 * advancement. Archiving is best-effort and never disturbs publication.
 */
@Component
@Primary
public final class ArchivingRoomEventSink implements RoomEventSink {
    private static final Logger log = LoggerFactory.getLogger(ArchivingRoomEventSink.class);

    private final StompRoomEventSink delegate;
    private final HandArchiveRecorder recorder;

    public ArchivingRoomEventSink(StompRoomEventSink delegate, HandArchiveRecorder recorder) {
        this.delegate = delegate;
        this.recorder = recorder;
    }

    @Override
    public PublicationReport publish(RoomChange change) {
        try {
            recorder.onChange(change);
        } catch (RuntimeException ex) {
            log.error("Hand archive recording failed roomId={}", change.roomId(), ex);
        }
        return delegate.publish(change);
    }
}
