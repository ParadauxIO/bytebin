package me.lucko.bytebin.dao;

import me.lucko.bytebin.usage.UsageEvent;
import me.lucko.bytebin.util.Metrics;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Data Access Object for the usage_events table.
 *
 * <p>Provides methods to insert individual events and batches of events.
 * Uses MyBatis annotation-based mappers via {@link UsageEventMapper}.</p>
 */
public class UsageEventDao {

    private static final Logger LOGGER = LogManager.getLogger(UsageEventDao.class);

    private final SqlSessionFactory sqlSessionFactory;

    public UsageEventDao(SqlSessionFactory sqlSessionFactory) {
        this.sqlSessionFactory = sqlSessionFactory;
    }

    /**
     * Inserts a single usage event.
     *
     * @param event the event to insert
     */
    public void insert(UsageEvent event) {
        try (SqlSession session = this.sqlSessionFactory.openSession(true)) {
            UsageEventMapper mapper = session.getMapper(UsageEventMapper.class);
            mapper.insert(event);
        } catch (Exception e) {
            LOGGER.error("[USAGE DB] Error inserting usage event", e);
            Metrics.DB_ERROR_COUNTER.labels("usage_insert").inc();
        }
    }

    /**
     * Inserts a batch of usage events in a single transaction.
     *
     * @param events the events to insert
     */
    public void insertBatch(Collection<UsageEvent> events) {
        if (events.isEmpty()) {
            return;
        }

        try (SqlSession session = this.sqlSessionFactory.openSession()) {
            UsageEventMapper mapper = session.getMapper(UsageEventMapper.class);
            mapper.insertBatch(events);
            session.commit();
        } catch (Exception e) {
            LOGGER.error("[USAGE DB] Error batch-inserting {} usage events", events.size(), e);
            Metrics.DB_ERROR_COUNTER.labels("usage_insertBatch").inc();
        }
    }

    /**
     * Counts events grouped by event type within a time range.
     *
     * @param sinceMillis the start timestamp (epoch millis, inclusive)
     * @param untilMillis the end timestamp (epoch millis, exclusive)
     * @return a list of maps with "event_type" and "count" keys
     */
    public List<Map<String, Object>> countByEventType(long sinceMillis, long untilMillis) {
        try (SqlSession session = this.sqlSessionFactory.openSession(true)) {
            UsageEventMapper mapper = session.getMapper(UsageEventMapper.class);
            return mapper.countByEventType(sinceMillis, untilMillis);
        } catch (Exception e) {
            LOGGER.error("[USAGE DB] Error counting events by type", e);
            Metrics.DB_ERROR_COUNTER.labels("usage_countByEventType").inc();
            return Collections.emptyList();
        }
    }

    /**
     * Counts unique IP addresses within a time range.
     *
     * @param sinceMillis the start timestamp (epoch millis, inclusive)
     * @param untilMillis the end timestamp (epoch millis, exclusive)
     * @return the number of unique IPs
     */
    public long countUniqueIps(long sinceMillis, long untilMillis) {
        try (SqlSession session = this.sqlSessionFactory.openSession(true)) {
            UsageEventMapper mapper = session.getMapper(UsageEventMapper.class);
            return mapper.countUniqueIps(sinceMillis, untilMillis);
        } catch (Exception e) {
            LOGGER.error("[USAGE DB] Error counting unique IPs", e);
            Metrics.DB_ERROR_COUNTER.labels("usage_countUniqueIps").inc();
            return 0;
        }
    }

    /**
     * Gets total content bytes posted within a time range.
     *
     * @param sinceMillis the start timestamp (epoch millis, inclusive)
     * @param untilMillis the end timestamp (epoch millis, exclusive)
     * @return total bytes posted
     */
    public long sumContentBytesPosted(long sinceMillis, long untilMillis) {
        try (SqlSession session = this.sqlSessionFactory.openSession(true)) {
            UsageEventMapper mapper = session.getMapper(UsageEventMapper.class);
            return mapper.sumContentBytesPosted(sinceMillis, untilMillis);
        } catch (Exception e) {
            LOGGER.error("[USAGE DB] Error summing content bytes", e);
            Metrics.DB_ERROR_COUNTER.labels("usage_sumContentBytes").inc();
            return 0;
        }
    }

    /**
     * Gets the total number of events within a time range.
     *
     * @param sinceMillis the start timestamp (epoch millis, inclusive)
     * @param untilMillis the end timestamp (epoch millis, exclusive)
     * @return the total event count
     */
    public long countTotal(long sinceMillis, long untilMillis) {
        try (SqlSession session = this.sqlSessionFactory.openSession(true)) {
            UsageEventMapper mapper = session.getMapper(UsageEventMapper.class);
            return mapper.countTotal(sinceMillis, untilMillis);
        } catch (Exception e) {
            LOGGER.error("[USAGE DB] Error counting total events", e);
            Metrics.DB_ERROR_COUNTER.labels("usage_countTotal").inc();
            return 0;
        }
    }

    /**
     * Lists recent usage events with optional event type filtering, newest first.
     *
     * @param limit     max number of events to return
     * @param offset    number of events to skip
     * @param eventType event type filter, or null for all types
     * @return paginated list of event rows
     */
    public List<Map<String, Object>> listRecent(int limit, int offset, String eventType) {
        try (SqlSession session = this.sqlSessionFactory.openSession(true)) {
            UsageEventMapper mapper = session.getMapper(UsageEventMapper.class);
            return mapper.listRecent(limit, offset, eventType);
        } catch (Exception e) {
            LOGGER.error("[USAGE DB] Error listing recent events", e);
            Metrics.DB_ERROR_COUNTER.labels("usage_listRecent").inc();
            return Collections.emptyList();
        }
    }

    /**
     * Counts usage events, optionally filtered by event type.
     *
     * @param eventType event type filter, or null for all types
     * @return the count
     */
    public long countEvents(String eventType) {
        try (SqlSession session = this.sqlSessionFactory.openSession(true)) {
            UsageEventMapper mapper = session.getMapper(UsageEventMapper.class);
            return mapper.countEvents(eventType);
        } catch (Exception e) {
            LOGGER.error("[USAGE DB] Error counting events", e);
            Metrics.DB_ERROR_COUNTER.labels("usage_countEvents").inc();
            return 0;
        }
    }

    /**
     * Gets event counts grouped by hour and event type within a time range.
     *
     * @param sinceMillis start timestamp (epoch millis, inclusive)
     * @param untilMillis end timestamp (epoch millis, exclusive)
     * @return list of rows with hour_millis, event_type, count
     */
    public List<Map<String, Object>> hourlyStats(long sinceMillis, long untilMillis) {
        try (SqlSession session = this.sqlSessionFactory.openSession(true)) {
            UsageEventMapper mapper = session.getMapper(UsageEventMapper.class);
            return mapper.hourlyStats(sinceMillis, untilMillis);
        } catch (Exception e) {
            LOGGER.error("[USAGE DB] Error querying hourly stats", e);
            Metrics.DB_ERROR_COUNTER.labels("usage_hourlyStats").inc();
            return Collections.emptyList();
        }
    }

    /**
     * Gets the top user agents by request count within a time range.
     *
     * @param sinceMillis the start timestamp (epoch millis, inclusive)
     * @param untilMillis the end timestamp (epoch millis, exclusive)
     * @param limit the max number of results
     * @return a list of maps with "user_agent" and "count" keys
     */
    public List<Map<String, Object>> topUserAgents(long sinceMillis, long untilMillis, int limit) {
        try (SqlSession session = this.sqlSessionFactory.openSession(true)) {
            UsageEventMapper mapper = session.getMapper(UsageEventMapper.class);
            return mapper.topUserAgents(sinceMillis, untilMillis, limit);
        } catch (Exception e) {
            LOGGER.error("[USAGE DB] Error querying top user agents", e);
            Metrics.DB_ERROR_COUNTER.labels("usage_topUserAgents").inc();
            return Collections.emptyList();
        }
    }
}
