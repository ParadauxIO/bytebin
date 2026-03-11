package me.lucko.bytebin.usage;

import me.lucko.bytebin.util.Metrics;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Collection;

/**
 * Database access layer for the usage_events table.
 *
 * <p>Provides methods to insert individual events and batches of events.
 * Uses the same MyBatis {@link SqlSessionFactory} as the content index database.</p>
 */
public class UsageEventDatabase {

    private static final Logger LOGGER = LogManager.getLogger(UsageEventDatabase.class);

    private final SqlSessionFactory sqlSessionFactory;

    public UsageEventDatabase(SqlSessionFactory sqlSessionFactory) {
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
}
