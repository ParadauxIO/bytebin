package me.lucko.bytebin.service;

import io.jooby.Context;
import me.lucko.bytebin.dao.UsageEventDao;
import me.lucko.bytebin.usage.UsageEvent;
import me.lucko.bytebin.util.Metrics;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Service for collecting and persisting {@link UsageEvent}s.
 *
 * <p>Events are queued in a bounded {@link ArrayBlockingQueue} and flushed to the
 * database on a configurable interval (default: every 10 seconds). This ensures that
 * recording metrics has negligible impact on request latency.</p>
 */
public class UsageEventService implements AutoCloseable {

    private static final Logger LOGGER = LogManager.getLogger(UsageEventService.class);

    /** Default flush interval in seconds */
    private static final int DEFAULT_FLUSH_INTERVAL = 10;

    // 19 parameters per row: 1000 rows remains below PostgreSQL's parameter limit.
    private static final int BATCH_SIZE = 1000;
    private final ArrayBlockingQueue<UsageEvent> queue = new ArrayBlockingQueue<>(10000);
    private final List<UsageEvent> pending = new ArrayList<>(BATCH_SIZE);
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
        if (!this.queue.offer(event)) {
            Metrics.USAGE_EVENTS_DROPPED_COUNTER.inc();
        }
    }

    /**
     * Writes one bounded batch. A failed batch stays pending ahead of new events.
     * Synchronization prevents shutdown and scheduled flushes from writing it twice.
     */
    private synchronized void flush() {
        if (this.pending.isEmpty()) {
            this.queue.drainTo(this.pending, BATCH_SIZE);
        }
        if (this.pending.isEmpty()) return;
        try {
            this.usageEventDao.insertBatch(this.pending);
            this.pending.clear();
        } catch (Exception e) {
            LOGGER.error("[USAGE] Failed to flush {} usage events, retaining batch for retry", this.pending.size(), e);
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

    /**
     * Returns true if the given User-Agent belongs to infrastructure monitoring
     * rather than a real visitor.
     *
     * <p>Kubernetes liveness/readiness probes and blackbox uptime probes request
     * the index page on a fixed schedule. Recording those as UI visits buries the
     * real traffic — they can easily account for the overwhelming majority of
     * rows — and skews every figure the admin portal and the daily report show.</p>
     *
     * @param userAgent the User-Agent header value, may be null
     * @return true if the request came from a monitoring agent
     */
    public static boolean isSyntheticAgent(String userAgent) {
        if (userAgent == null) {
            return false;
        }
        String ua = userAgent.toLowerCase(Locale.ROOT);
        return ua.startsWith("kube-probe/")
                || ua.startsWith("blackbox exporter")
                || ua.startsWith("blackbox_exporter")
                || ua.startsWith("blackbox-exporter/")
                || ua.startsWith("prometheus/")
                || ua.startsWith("go-http-client/"); // blackbox exporter's default UA on older releases
    }

    @Override
    public synchronized void close() {
        // Drain all batches on graceful shutdown, allowing one retry per failed batch.
        while (!this.pending.isEmpty() || !this.queue.isEmpty()) {
            flush();
            if (!this.pending.isEmpty()) {
                flush();
                if (!this.pending.isEmpty()) {
                    LOGGER.error("[USAGE] {} usage events will be lost on shutdown",
                            this.pending.size() + this.queue.size());
                    break;
                }
            }
        }
    }
}
