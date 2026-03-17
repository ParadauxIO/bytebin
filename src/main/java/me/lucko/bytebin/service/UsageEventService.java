package me.lucko.bytebin.service;

import io.jooby.Context;
import me.lucko.bytebin.dao.UsageEventDao;
import me.lucko.bytebin.usage.UsageEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Service for collecting and persisting {@link UsageEvent}s.
 *
 * <p>Events are queued in a lock-free {@link ConcurrentLinkedQueue} and flushed to the
 * database on a configurable interval (default: every 10 seconds). This ensures that
 * recording metrics has negligible impact on request latency.</p>
 */
public class UsageEventService implements AutoCloseable {

    private static final Logger LOGGER = LogManager.getLogger(UsageEventService.class);

    /** Default flush interval in seconds */
    private static final int DEFAULT_FLUSH_INTERVAL = 10;

    private final Queue<UsageEvent> queue = new ConcurrentLinkedQueue<>();
    private final UsageEventDao usageEventDao;

    public UsageEventService(UsageEventDao usageEventDao, ScheduledExecutorService executor) {
        this(usageEventDao, executor, DEFAULT_FLUSH_INTERVAL);
    }

    public UsageEventService(UsageEventDao usageEventDao, ScheduledExecutorService executor, int flushIntervalSeconds) {
        this.usageEventDao = usageEventDao;
        executor.scheduleAtFixedRate(this::flush, flushIntervalSeconds, flushIntervalSeconds, TimeUnit.SECONDS);
    }

    /**
     * Records a usage event. This method is non-blocking and safe to call from any thread.
     *
     * @param event the event to record
     */
    public void record(UsageEvent event) {
        this.queue.offer(event);
    }

    /**
     * Drains the queue and batch-inserts all pending events into the database.
     *
     * <p>If the insert fails, all drained events are re-queued so they will be
     * retried on the next scheduled flush rather than silently dropped.</p>
     */
    private void flush() {
        List<UsageEvent> events = new ArrayList<>();
        for (UsageEvent e; (e = this.queue.poll()) != null; ) {
            events.add(e);
        }
        if (events.isEmpty()) {
            return;
        }
        LOGGER.debug("[USAGE] Flushing {} usage events to database", events.size());
        try {
            this.usageEventDao.insertBatch(events);
        } catch (Exception e) {
            LOGGER.error("[USAGE] Failed to flush {} usage events to database, re-queueing for next attempt", events.size(), e);
            this.queue.addAll(events);
        }

        int queueSize = this.queue.size();
        if (queueSize > 1000) {
            LOGGER.warn("[USAGE] Usage event queue is large: {} pending events", queueSize);
        }
    }

    /**
     * Extracts common request metadata from a Jooby {@link Context} into a pre-populated builder.
     *
     * <p>Populates: User-Agent, Origin, Host, Referer, Accept-Language, HTTP method.</p>
     *
     * @param ctx the Jooby request context
     * @param eventType the event type string
     * @return a pre-populated builder
     */
    public static UsageEvent.Builder builderFromContext(Context ctx, String eventType) {
        return UsageEvent.builder(eventType)
                .userAgent(ctx.header("User-Agent").valueOrNull())
                .origin(ctx.header("Origin").value("null"))
                .host(ctx.getHostAndPort())
                .referer(ctx.header("Referer").valueOrNull())
                .acceptLanguage(ctx.header("Accept-Language").valueOrNull())
                .httpMethod(ctx.getMethod());
    }

    @Override
    public void close() {
        flush();
        // If a transient failure re-queued events, attempt one final retry before shutdown.
        if (!this.queue.isEmpty()) {
            LOGGER.warn("[USAGE] Retrying flush of {} re-queued events before shutdown", this.queue.size());
            flush();
        }
        if (!this.queue.isEmpty()) {
            LOGGER.error("[USAGE] {} usage events could not be persisted and will be lost on shutdown", this.queue.size());
        }
    }
}
