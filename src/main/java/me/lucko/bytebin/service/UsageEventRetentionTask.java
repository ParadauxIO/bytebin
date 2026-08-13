package me.lucko.bytebin.service;

import me.lucko.bytebin.dao.UsageEventDao;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Scheduled task that prunes usage events older than the configured retention
 * period.
 *
 * <p>The usage_events table gains a row for every UI visit and API call, so
 * without pruning it grows without bound. Rows are deleted in bounded batches
 * so a backlog (or the first run against an existing table) never turns into a
 * single long-running statement against a shared database.</p>
 */
public class UsageEventRetentionTask implements Runnable {

    private static final Logger LOGGER = LogManager.getLogger(UsageEventRetentionTask.class);

    /** Number of rows deleted per statement. */
    private static final int BATCH_SIZE = 10_000;

    /** Upper bound on batches per run, so one run can't loop indefinitely. */
    private static final int MAX_BATCHES_PER_RUN = 500;

    /** Delay before the first run, to keep it clear of application startup. */
    private static final Duration INITIAL_DELAY = Duration.ofMinutes(15);

    private static final Duration INTERVAL = Duration.ofHours(24);

    private final UsageEventDao usageEventDao;
    private final int retentionDays;

    public UsageEventRetentionTask(UsageEventDao usageEventDao, int retentionDays) {
        this.usageEventDao = usageEventDao;
        this.retentionDays = retentionDays;
    }

    /**
     * Schedules the prune to run daily.
     *
     * @param executor the executor to schedule on
     */
    public void schedule(ScheduledExecutorService executor) {
        executor.scheduleAtFixedRate(this, INITIAL_DELAY.toMinutes(), INTERVAL.toMinutes(), TimeUnit.MINUTES);
    }

    @Override
    public void run() {
        long cutoff = System.currentTimeMillis() - Duration.ofDays(this.retentionDays).toMillis();

        int totalDeleted = 0;
        for (int batch = 0; batch < MAX_BATCHES_PER_RUN; batch++) {
            int deleted = this.usageEventDao.deleteOlderThan(cutoff, BATCH_SIZE);
            if (deleted < 0) {
                // the DAO already logged the cause; leave the rest for the next run
                LOGGER.warn("[USAGE RETENTION] Prune aborted after deleting {} events", totalDeleted);
                return;
            }

            totalDeleted += deleted;
            if (deleted < BATCH_SIZE) {
                break;
            }
        }

        if (totalDeleted > 0) {
            LOGGER.info("[USAGE RETENTION] Deleted {} usage events older than {} days", totalDeleted, this.retentionDays);
        } else {
            LOGGER.debug("[USAGE RETENTION] No usage events older than {} days", this.retentionDays);
        }
    }

}
