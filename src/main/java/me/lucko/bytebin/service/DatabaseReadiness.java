package me.lucko.bytebin.service;

import io.prometheus.client.Gauge;
import me.lucko.bytebin.util.Metrics;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Tracks whether the PostgreSQL metadata index is reachable, for the readiness probe.
 *
 * <p>The database is checked in the background, and {@link #isReady()} only reads the
 * cached result. Probes therefore return immediately even when borrowing a pool
 * connection would block for the full pool timeout. A result older than
 * {@code maxAge} counts as not ready, so a check that hangs also marks the
 * instance unready.</p>
 */
public final class DatabaseReadiness {

    private static final Logger LOGGER = LogManager.getLogger(DatabaseReadiness.class);

    private static final Duration DEFAULT_INTERVAL = Duration.ofSeconds(5);
    private static final Duration DEFAULT_MAX_AGE = Duration.ofSeconds(15);
    private static final int VALIDATION_TIMEOUT_SECONDS = 2;

    private final DataSource dataSource;
    private final long maxAgeNanos;
    private final LongSupplier nanoClock;

    private volatile boolean lastCheckSucceeded = false;
    private volatile long lastSuccessNanos;

    public DatabaseReadiness(DataSource dataSource) {
        this(dataSource, DEFAULT_MAX_AGE, System::nanoTime);
    }

    DatabaseReadiness(DataSource dataSource, Duration maxAge, LongSupplier nanoClock) {
        this.dataSource = dataSource;
        this.maxAgeNanos = maxAge.toNanos();
        this.nanoClock = nanoClock;
    }

    /**
     * Runs one check now, then repeats it in the background, and exports
     * {@link #isReady()} as the {@code bytebin_database_ready} gauge.
     *
     * @param executor the executor to run checks on
     */
    public void start(ScheduledExecutorService executor) {
        Metrics.DATABASE_READY_GAUGE.setChild(new Gauge.Child() {
            @Override
            public double get() {
                return isReady() ? 1 : 0;
            }
        });
        check();
        long interval = DEFAULT_INTERVAL.toMillis();
        executor.scheduleWithFixedDelay(this::check, interval, interval, TimeUnit.MILLISECONDS);
    }

    /**
     * Borrows a pool connection and validates it, recording the result.
     */
    void check() {
        boolean ok;
        Exception failure = null;
        try (Connection connection = this.dataSource.getConnection()) {
            ok = connection.isValid(VALIDATION_TIMEOUT_SECONDS);
        } catch (Exception e) {
            ok = false;
            failure = e;
        }

        if (ok) {
            this.lastSuccessNanos = this.nanoClock.getAsLong();
        }
        boolean previous = this.lastCheckSucceeded;
        this.lastCheckSucceeded = ok;

        // log transitions only, so an outage does not log every few seconds
        if (ok && !previous) {
            LOGGER.info("[DB] Database reachable, instance is ready");
        } else if (!ok && previous) {
            LOGGER.warn("[DB] Database unreachable, instance is not ready", failure);
        }
    }

    /**
     * @return true if the most recent check succeeded and is not older than the maximum age
     */
    public boolean isReady() {
        return this.lastCheckSucceeded && this.nanoClock.getAsLong() - this.lastSuccessNanos <= this.maxAgeNanos;
    }
}
