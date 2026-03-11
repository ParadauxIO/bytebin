package me.lucko.bytebin.usage;

import io.jooby.Context;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Collects {@link UsageEvent}s asynchronously and flushes them to the database in batches.
 *
 * <p>Events are queued in a lock-free {@link ConcurrentLinkedQueue} and flushed to the
 * database on a configurable interval (default: every 10 seconds). This ensures that
 * recording metrics has negligible impact on request latency.</p>
 */
public class UsageEventCollector implements AutoCloseable {

    private static final Logger LOGGER = LogManager.getLogger(UsageEventCollector.class);

    /** Default flush interval in seconds */
    private static final int DEFAULT_FLUSH_INTERVAL = 10;

    private final Queue<UsageEvent> queue = new ConcurrentLinkedQueue<>();
    private final UsageEventDatabase database;

    public UsageEventCollector(UsageEventDatabase database, ScheduledExecutorService executor) {
        this(database, executor, DEFAULT_FLUSH_INTERVAL);
    }

    public UsageEventCollector(UsageEventDatabase database, ScheduledExecutorService executor, int flushIntervalSeconds) {
        this.database = database;
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
     */
    private void flush() {
        List<UsageEvent> events = new ArrayList<>();
        for (UsageEvent e; (e = this.queue.poll()) != null; ) {
            events.add(e);
        }
        if (!events.isEmpty()) {
            LOGGER.debug("[USAGE] Flushing {} usage events to database", events.size());
            this.database.insertBatch(events);
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
    }
}
