package me.lucko.bytebin.dao;

import io.prometheus.client.Histogram;
import me.lucko.bytebin.content.Content;
import me.lucko.bytebin.content.ContentStorageMetric;
import me.lucko.bytebin.content.storage.StorageBackend;
import me.lucko.bytebin.util.Metrics;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Data Access Object for the content index.
 *
 * <p>The content index is a database storing metadata about the content stored in bytebin.
 * It is merely an index, and can be regenerated at any time from the raw data (stored in the backend).
 * The primary use is to track content expiry times, and to determine which backend bytebin should
 * read from if the content isn't already cached in memory. It is also used for metrics.</p>
 *
 * <p>Backed by PostgreSQL via MyBatis annotation-based mappers.</p>
 */
public class ContentDao implements AutoCloseable {

    private static final Logger LOGGER = LogManager.getLogger(ContentDao.class);

    /**
     * Initialises the content DAO. If the database is empty (no rows in the content
     * table), the index is rebuilt by scanning all storage backends.
     *
     * @param sqlSessionFactory the MyBatis session factory (already migrated by Flyway)
     * @param backends the storage backends to rebuild from if needed
     * @return the initialised DAO
     */
    public static ContentDao initialise(SqlSessionFactory sqlSessionFactory, Collection<StorageBackend> backends) {
        ContentDao dao = new ContentDao(sqlSessionFactory);

        // Check if the index is empty and rebuild from backends if so.
        boolean empty;
        try (SqlSession session = sqlSessionFactory.openSession()) {
            ContentMapper mapper = session.getMapper(ContentMapper.class);
            empty = mapper.getExpired(Long.MAX_VALUE).isEmpty()
                    && mapper.countByTypeAndBackend().isEmpty();
        }

        if (empty) {
            LOGGER.info("[INDEX DB] Database appears empty, rebuilding index from storage backends...");
            for (StorageBackend backend : backends) {
                try {
                    List<Content> metadata = backend.list().collect(Collectors.toList());
                    dao.putAll(metadata);
                    LOGGER.info("[INDEX DB] Indexed {} entries from '{}' backend", metadata.size(), backend.getBackendId());
                } catch (Exception e) {
                    LOGGER.error("[INDEX DB] Error rebuilding index for " + backend.getBackendId() + " backend", e);
                }
            }
        }
        return dao;
    }

    private static final Histogram.Child DURATION_HISTOGRAM_PUT = Metrics.DB_TRANSACTION_DURATION_HISTOGRAM.labels("put");
    private static final Histogram.Child DURATION_HISTOGRAM_GET = Metrics.DB_TRANSACTION_DURATION_HISTOGRAM.labels("get");
    private static final Histogram.Child DURATION_HISTOGRAM_PUT_ALL = Metrics.DB_TRANSACTION_DURATION_HISTOGRAM.labels("putAll");
    private static final Histogram.Child DURATION_HISTOGRAM_REMOVE = Metrics.DB_TRANSACTION_DURATION_HISTOGRAM.labels("remove");
    private static final Histogram.Child DURATION_HISTOGRAM_GET_EXPIRED = Metrics.DB_TRANSACTION_DURATION_HISTOGRAM.labels("getExpired");
    private static final Histogram.Child DURATION_HISTOGRAM_QUERY_METRICS = Metrics.DB_TRANSACTION_DURATION_HISTOGRAM.labels("queryMetrics");
    private static final Histogram.Child DURATION_HISTOGRAM_INCREMENT_READ_COUNT = Metrics.DB_TRANSACTION_DURATION_HISTOGRAM.labels("incrementReadCount");

    private final SqlSessionFactory sqlSessionFactory;

    public ContentDao(SqlSessionFactory sqlSessionFactory) {
        this.sqlSessionFactory = sqlSessionFactory;
    }

    public void put(Content content) {
        try (Histogram.Timer ignored = DURATION_HISTOGRAM_PUT.startTimer();
             SqlSession session = this.sqlSessionFactory.openSession(true)) {
            ContentMapper mapper = session.getMapper(ContentMapper.class);
            mapper.upsert(content);
            LOGGER.debug("[INDEX DB] put: key={}", content.getKey());
        } catch (Exception e) {
            LOGGER.error("[INDEX DB] Error in put for key={}", content.getKey(), e);
            Metrics.DB_ERROR_COUNTER.labels("put").inc();
        }
    }

    public Content get(String key) {
        try (Histogram.Timer ignored = DURATION_HISTOGRAM_GET.startTimer();
             SqlSession session = this.sqlSessionFactory.openSession(true)) {
            ContentMapper mapper = session.getMapper(ContentMapper.class);
            Content result = mapper.getByKey(key);
            LOGGER.debug("[INDEX DB] get: key={} found={}", key, result != null);
            return result;
        } catch (Exception e) {
            LOGGER.error("[INDEX DB] Error in get for key={}", key, e);
            Metrics.DB_ERROR_COUNTER.labels("get").inc();
            return null;
        }
    }

    public void putAll(Collection<Content> content) {
        try (Histogram.Timer ignored = DURATION_HISTOGRAM_PUT_ALL.startTimer();
             SqlSession session = this.sqlSessionFactory.openSession()) {
            ContentMapper mapper = session.getMapper(ContentMapper.class);
            for (Content c : content) {
                mapper.insert(c);
            }
            session.commit();
            LOGGER.debug("[INDEX DB] putAll: inserted {} entries", content.size());
        } catch (Exception e) {
            LOGGER.error("[INDEX DB] Error in putAll for {} entries", content.size(), e);
            Metrics.DB_ERROR_COUNTER.labels("putAll").inc();
        }
    }

    public void remove(String key) {
        try (Histogram.Timer ignored = DURATION_HISTOGRAM_REMOVE.startTimer();
             SqlSession session = this.sqlSessionFactory.openSession(true)) {
            ContentMapper mapper = session.getMapper(ContentMapper.class);
            mapper.deleteByKey(key);
            LOGGER.debug("[INDEX DB] remove: key={}", key);
        } catch (Exception e) {
            LOGGER.error("[INDEX DB] Error in remove for key={}", key, e);
            Metrics.DB_ERROR_COUNTER.labels("remove").inc();
        }
    }

    public Collection<Content> getExpired() {
        try (Histogram.Timer ignored = DURATION_HISTOGRAM_GET_EXPIRED.startTimer();
             SqlSession session = this.sqlSessionFactory.openSession(true)) {
            ContentMapper mapper = session.getMapper(ContentMapper.class);
            Collection<Content> expired = mapper.getExpired(System.currentTimeMillis());
            LOGGER.debug("[INDEX DB] getExpired: found {} entries", expired.size());
            return expired;
        } catch (Exception e) {
            LOGGER.error("[INDEX DB] Error in getExpired", e);
            Metrics.DB_ERROR_COUNTER.labels("getExpired").inc();
            return Collections.emptyList();
        }
    }

    /**
     * Atomically increments the read count for the given key in the database
     * and returns the new value.
     *
     * @param key the content key
     * @return the new read count, or -1 if the key was not found
     */
    public int incrementReadCount(String key) {
        try (Histogram.Timer ignored = DURATION_HISTOGRAM_INCREMENT_READ_COUNT.startTimer();
             SqlSession session = this.sqlSessionFactory.openSession(true)) {
            ContentMapper mapper = session.getMapper(ContentMapper.class);
            Integer result = mapper.incrementReadCount(key);
            LOGGER.debug("[INDEX DB] incrementReadCount: key={} newCount={}", key, result);
            return result != null ? result : -1;
        } catch (Exception e) {
            LOGGER.error("[INDEX DB] Error in incrementReadCount for key={}", key, e);
            Metrics.DB_ERROR_COUNTER.labels("incrementReadCount").inc();
            return -1;
        }
    }

    public void recordMetrics() {
        try (Histogram.Timer ignored = DURATION_HISTOGRAM_QUERY_METRICS.startTimer();
             SqlSession session = this.sqlSessionFactory.openSession(true)) {
            ContentMapper mapper = session.getMapper(ContentMapper.class);

            List<ContentStorageMetric> counts = mapper.countByTypeAndBackend();
            for (ContentStorageMetric m : counts) {
                Metrics.STORED_CONTENT_COUNT_GAUGE.labels(m.getContentType(), m.getBackendId()).set(m.getValue());
            }

            List<ContentStorageMetric> sizes = mapper.sumSizeByTypeAndBackend();
            for (ContentStorageMetric m : sizes) {
                Metrics.STORED_CONTENT_SIZE_GAUGE.labels(m.getContentType(), m.getBackendId()).set(m.getValue());
            }
        } catch (Exception e) {
            LOGGER.error("[INDEX DB] Error in recordMetrics", e);
            Metrics.DB_ERROR_COUNTER.labels("queryMetrics").inc();
        }
    }

    public long countAll() {
        try (SqlSession session = this.sqlSessionFactory.openSession(true)) {
            return session.getMapper(ContentMapper.class).countAll();
        } catch (Exception e) {
            LOGGER.error("[INDEX DB] Error in countAll", e);
            Metrics.DB_ERROR_COUNTER.labels("countAll").inc();
            return 0;
        }
    }

    public long sumStorageBytes() {
        try (SqlSession session = this.sqlSessionFactory.openSession(true)) {
            return session.getMapper(ContentMapper.class).sumStorageBytes();
        } catch (Exception e) {
            LOGGER.error("[INDEX DB] Error in sumStorageBytes", e);
            Metrics.DB_ERROR_COUNTER.labels("sumStorageBytes").inc();
            return 0;
        }
    }

    public List<Content> listAll(int limit, int offset) {
        try (SqlSession session = this.sqlSessionFactory.openSession(true)) {
            return session.getMapper(ContentMapper.class).listAll(limit, offset);
        } catch (Exception e) {
            LOGGER.error("[INDEX DB] Error in listAll", e);
            Metrics.DB_ERROR_COUNTER.labels("listAll").inc();
            return Collections.emptyList();
        }
    }

    @Override
    public void close() throws Exception {
        // The SqlSessionFactory doesn't need closing directly.
        // The underlying HikariCP DataSource is closed separately in Bytebin.close().
    }
}
