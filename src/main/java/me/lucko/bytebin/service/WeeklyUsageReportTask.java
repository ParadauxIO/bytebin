package me.lucko.bytebin.service;

import me.lucko.bytebin.dao.UsageEventDao;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.LocalTime;
import java.time.temporal.TemporalAdjusters;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Scheduled task that generates a weekly usage metrics report and sends it
 * to a Discord webhook as an embed message every Monday at 8:00 AM local time.
 */
public class WeeklyUsageReportTask {

    private static final Logger LOGGER = LogManager.getLogger(WeeklyUsageReportTask.class);
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final UsageEventDao usageEventDao;
    private final DiscordWebhookService discordWebhookService;
    private final String instanceLabel;

    public WeeklyUsageReportTask(UsageEventDao usageEventDao, DiscordWebhookService discordWebhookService) {
        this(usageEventDao, discordWebhookService, null);
    }

    /**
     * @param instanceLabel names the instance in the embed footer, so several
     *                      deployments reporting to the same webhook can be told
     *                      apart. May be null.
     */
    public WeeklyUsageReportTask(UsageEventDao usageEventDao, DiscordWebhookService discordWebhookService, String instanceLabel) {
        this.usageEventDao = usageEventDao;
        this.discordWebhookService = discordWebhookService;
        this.instanceLabel = instanceLabel;
    }

    /**
     * Schedules the weekly report to run at 8:00 AM local time every Monday.
     *
     * <p>Rather than using a fixed seven-day period (which drifts across DST transitions),
     * each run recomputes the exact delay to the next Monday at 8:00 AM wall-clock time and
     * schedules a fresh one-shot task. This guarantees the report always fires at
     * 8:00 AM local time regardless of DST changes or task execution delays.</p>
     *
     * @param executor the executor to schedule on
     */
    public void schedule(ScheduledExecutorService executor) {
        long initialDelay = computeDelayUntilNextReport(ZonedDateTime.now());
        LOGGER.info("[DISCORD REPORT] Scheduled weekly usage report, first run in {} minutes", initialDelay / 60_000);
        executor.schedule(() -> runAndReschedule(executor), initialDelay, TimeUnit.MILLISECONDS);
    }

    private void runAndReschedule(ScheduledExecutorService executor) {
        run();
        long delay = computeDelayUntilNextReport(ZonedDateTime.now());
        LOGGER.info("[DISCORD REPORT] Next weekly usage report scheduled in {} minutes", delay / 60_000);
        executor.schedule(() -> runAndReschedule(executor), delay, TimeUnit.MILLISECONDS);
    }

    /**
     * Computes the delay until the next Monday at 8:00 AM in the supplied zone.
     */
    static long computeDelayUntilNextReport(ZonedDateTime now) {
        ZonedDateTime nextReport = now.with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY))
                .with(LocalTime.of(8, 0));
        if (!nextReport.isAfter(now)) {
            nextReport = nextReport.plusWeeks(1);
        }
        return Duration.between(now, nextReport).toMillis();
    }

    /**
     * Generates and sends the weekly usage report for the previous week.
     */
    public void run() {
        try {
            LOGGER.info("[DISCORD REPORT] Generating weekly usage report...");

            // report covers the previous week
            ZonedDateTime until = ZonedDateTime.now();
            ZonedDateTime since = until.minusWeeks(1);
            long untilMillis = until.toInstant().toEpochMilli();
            long sinceMillis = since.toInstant().toEpochMilli();

            // Exclude monitoring traffic from every report metric, including historical events.
            long totalEvents = this.usageEventDao.countTotal(sinceMillis, untilMillis, true);
            long uniqueIps = this.usageEventDao.countUniqueIps(sinceMillis, untilMillis, true);
            long bytesPosted = this.usageEventDao.sumContentBytesPosted(sinceMillis, untilMillis, true);
            List<Map<String, Object>> eventTypeCounts = this.usageEventDao.countByEventType(sinceMillis, untilMillis, true);
            List<Map<String, Object>> topAgents = this.usageEventDao.topUserAgents(sinceMillis, untilMillis, 5, true);

            // build the event type breakdown string
            StringBuilder breakdown = new StringBuilder();
            for (Map<String, Object> row : eventTypeCounts) {
                String eventType = String.valueOf(row.get("event_type"));
                long count = ((Number) row.get("count")).longValue();
                breakdown.append(String.format("`%-12s` %,d%n", eventType, count));
            }
            if (breakdown.isEmpty()) {
                breakdown.append("No events recorded");
            }

            // build the top user agents string
            StringBuilder agents = new StringBuilder();
            for (Map<String, Object> row : topAgents) {
                String agent = String.valueOf(row.get("user_agent"));
                long count = ((Number) row.get("count")).longValue();
                // truncate long user agent strings
                if (agent.length() > 50) {
                    agent = agent.substring(0, 47) + "...";
                }
                agents.append(String.format("`%s` - %,d%n", agent, count));
            }
            if (agents.isEmpty()) {
                agents.append("No data");
            }

            // build the Discord embed
            List<Map<String, Object>> fields = new ArrayList<>();
            fields.add(embedField("Total Requests", String.format("%,d", totalEvents), true));
            fields.add(embedField("Unique IPs", String.format("%,d", uniqueIps), true));
            fields.add(embedField("Data Uploaded", formatBytes(bytesPosted), true));
            fields.add(embedField("Event Breakdown", breakdown.toString(), false));
            fields.add(embedField("Top User Agents", agents.toString(), false));

            Map<String, Object> embed = new LinkedHashMap<>();
            embed.put("title", "Weekly Usage Report");
            embed.put("description", "Usage metrics from **" + since.format(DATE_FORMAT) + "** to **"
                    + until.format(DATE_FORMAT) + "** (previous week)");
            embed.put("color", 0x5865F2); // Discord blurple
            embed.put("fields", fields);
            embed.put("footer", Map.of("text", this.instanceLabel == null || this.instanceLabel.isBlank()
                    ? "bytebin"
                    : "bytebin · " + this.instanceLabel));
            embed.put("timestamp", java.time.Instant.now().toString());

            this.discordWebhookService.sendEmbeds(List.of(embed));
            LOGGER.info("[DISCORD REPORT] Weekly usage report sent successfully");

        } catch (Exception e) {
            LOGGER.error("[DISCORD REPORT] Failed to generate or send weekly usage report", e);
        }
    }

    private static Map<String, Object> embedField(String name, String value, boolean inline) {
        Map<String, Object> field = new LinkedHashMap<>();
        field.put("name", name);
        field.put("value", value);
        field.put("inline", inline);
        return field;
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        } else if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        } else if (bytes < 1024L * 1024 * 1024) {
            return String.format("%.2f MB", bytes / (1024.0 * 1024));
        } else {
            return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
        }
    }
}
