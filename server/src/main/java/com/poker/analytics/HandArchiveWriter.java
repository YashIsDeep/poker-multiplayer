package com.poker.analytics;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.channels.FileChannel;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Durable local sink for completed hands. Records are appended to a daily
 * {@code hands-YYYY-MM-DD.jsonl.gz} file, one JSON line per hand, each batch
 * written as an independent gzip member and fsynced. Appending whole gzip
 * members keeps the file a valid gzip stream after a crash, and the archive
 * touches only the filesystem so it keeps working when the database is down.
 *
 * <p>All failures are contained here: gameplay threads only enqueue.</p>
 */
@Component
public final class HandArchiveWriter implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(HandArchiveWriter.class);
    private static final int QUEUE_CAPACITY = 10_000;
    private static final int MAX_BATCH = 64;

    private final HandArchiveProperties properties;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final LinkedBlockingQueue<HandArchiveRecord> queue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
    private final Thread worker;
    private volatile boolean running = true;
    private FileChannel channel;
    private LocalDate channelDate;

    public HandArchiveWriter(HandArchiveProperties properties, ObjectMapper mapper, Clock clock) {
        this.properties = properties;
        this.mapper = mapper;
        this.clock = clock;
        this.worker = new Thread(this::drainLoop, "hand-archive-writer");
        this.worker.setDaemon(true);
        if (properties.enabled()) {
            this.worker.start();
        }
    }

    /** Never blocks and never throws; a full queue drops the hand with a log. */
    public void append(HandArchiveRecord record) {
        if (!properties.enabled()) return;
        if (!queue.offer(record)) {
            log.error("Hand archive queue full; dropping roomId={} handId={}",
                    record.roomId(), record.handId());
        }
    }

    private void drainLoop() {
        while (running || !queue.isEmpty()) {
            try {
                HandArchiveRecord first = queue.poll(250, TimeUnit.MILLISECONDS);
                if (first == null) continue;
                List<HandArchiveRecord> batch = new ArrayList<>();
                batch.add(first);
                queue.drainTo(batch, MAX_BATCH - 1);
                writeBatch(batch);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void writeBatch(List<HandArchiveRecord> batch) {
        // Files are named by the hand's own completion date, so a batch that
        // straddles midnight (or a delayed drain) still lands in the right file.
        LinkedHashMap<LocalDate, List<HandArchiveRecord>> byDate = new LinkedHashMap<>();
        for (HandArchiveRecord record : batch) {
            byDate.computeIfAbsent(fileDate(record), ignored -> new ArrayList<>()).add(record);
        }
        byDate.forEach((date, records) -> {
            byte[] member;
            try {
                member = gzipMember(records);
            } catch (IOException | RuntimeException ex) {
                log.error("Hand archive serialization failed; dropping {} hand(s)", records.size(), ex);
                return;
            }
            try {
                FileChannel out = channelFor(date);
                out.write(ByteBuffer.wrap(member));
                out.force(false);
            } catch (IOException | RuntimeException ex) {
                closeChannelQuietly();
                log.error("Hand archive write failed; dropping {} hand(s)", records.size(), ex);
            }
        });
    }

    private LocalDate fileDate(HandArchiveRecord record) {
        Instant at = record.completedAt() != null ? record.completedAt() : clock.instant();
        return LocalDate.ofInstant(at, ZoneOffset.UTC);
    }

    private byte[] gzipMember(List<HandArchiveRecord> batch) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(4096);
        try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
            for (HandArchiveRecord record : batch) {
                gzip.write(mapper.writeValueAsBytes(record));
                gzip.write('\n');
            }
        }
        return bytes.toByteArray();
    }

    private FileChannel channelFor(LocalDate date) throws IOException {
        if (channel != null && channel.isOpen() && date.equals(channelDate)) {
            return channel;
        }
        closeChannelQuietly();
        Path directory = Path.of(properties.directory());
        Files.createDirectories(directory);
        Path file = directory.resolve("hands-" + date + ".jsonl.gz");
        channel = FileChannel.open(file,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
        channelDate = date;
        return channel;
    }

    private void closeChannelQuietly() {
        if (channel != null) {
            try {
                channel.close();
            } catch (IOException ignored) {
                // The channel is being discarded either way.
            }
            channel = null;
            channelDate = null;
        }
    }

    @PreDestroy
    @Override
    public void close() {
        running = false;
        try {
            worker.join(TimeUnit.SECONDS.toMillis(5));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        synchronized (this) {
            List<HandArchiveRecord> remaining = new ArrayList<>();
            queue.drainTo(remaining);
            if (!remaining.isEmpty() && properties.enabled()) writeBatch(remaining);
            closeChannelQuietly();
        }
    }
}
