package me.lucko.bytebin.service;

import me.lucko.bytebin.dao.UsageEventDao;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Scheduled task that generates a daily usage metrics report and sends it
 * to a Discord webhook as an embed message every morning at 8:00 AM local time.
 */
public class DailyUsageReportTask {

    private static final Logger LOGGER = LogManager.getLogger(DailyUsageReportTask.class);
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final UsageEventDao usageEventDao;
    private final DiscordWebhookService discordWebhookService;

    public DailyUsageReportTask(UsageEventDao usageEventDao, DiscordWebhookService discordWebhookService) {
        this.usageEventDao = usageEventDao;
        this.discordWebhookService = discordWebhookService;
    }

    /**
     * Schedules the daily report to run at 8:00 AM local time every day.
     *
     * @param executor the executor to schedule on
     */
    public void schedule(ScheduledExecutorService executor) {
        long initialDelay = computeDelayUntilNext8am();
        LOGGER.info("[DISCORD REPORT] Scheduled daily usage report, first run in {} minutes", initialDelay / 60_000);

        executor.scheduleAtFixedRate(this::run, initialDelay, TimeUnit.DAYS.toMillis(1), TimeUnit.MILLISECONDS);
    }

    /**
     * Computes the delay in milliseconds from now until the next 8:00 AM local time.
     */
    static long computeDelayUntilNext8am() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime next8am = LocalDateTime.of(now.toLocalDate(), LocalTime.of(8, 0));

        // if it's already past 8am today, schedule for tomorrow
        if (now.isAfter(next8am)) {
            next8am = next8am.plusDays(1);
        }

        long nowMillis = now.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        long targetMillis = next8am.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        return targetMillis - nowMillis;
    }

    /**
     * Generates and sends the daily usage report for the previous 24 hours.
     */
    public void run() {
        try {
            LOGGER.info("[DISCORD REPORT] Generating daily usage report...");

            // report covers the previous 24 hours
            long untilMillis = System.currentTimeMillis();
            long sinceMillis = untilMillis - TimeUnit.DAYS.toMillis(1);

            LocalDate reportDate = LocalDate.now().minusDays(1);

            // query usage data
            long totalEvents = this.usageEventDao.countTotal(sinceMillis, untilMillis);
            long uniqueIps = this.usageEventDao.countUniqueIps(sinceMillis, untilMillis);
            long bytesPosted = this.usageEventDao.sumContentBytesPosted(sinceMillis, untilMillis);
            List<Map<String, Object>> eventTypeCounts = this.usageEventDao.countByEventType(sinceMillis, untilMillis);
            List<Map<String, Object>> topAgents = this.usageEventDao.topUserAgents(sinceMillis, untilMillis, 5);

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
            embed.put("title", "Daily Usage Report");
            embed.put("description", "Usage metrics for **" + reportDate.format(DATE_FORMAT) + "** (previous 24 hours)");
            embed.put("color", 0x5865F2); // Discord blurple
            embed.put("fields", fields);
            embed.put("footer", Map.of("text", "bytebin"));
            embed.put("timestamp", java.time.Instant.now().toString());

            this.discordWebhookService.sendEmbeds(List.of(embed));
            LOGGER.info("[DISCORD REPORT] Daily usage report sent successfully");

        } catch (Exception e) {
            LOGGER.error("[DISCORD REPORT] Failed to generate or send daily usage report", e);
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
