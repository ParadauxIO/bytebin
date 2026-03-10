/*
 * This file is part of bytebin, licensed under the MIT License.
 *
 *  Copyright (c) lucko (Luck) <luck@lucko.me>
 *  Copyright (c) contributors
 *
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *
 *  The above copyright notice and this permission notice shall be included in all
 *  copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 *  SOFTWARE.
 */

package me.lucko.bytebin.content;

import io.prometheus.client.Histogram;
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
 * The content index is a database storing metadata about the content stored in bytebin.
 *
 * <p>It is merely an index, and can be regenerated at any time from the raw data (stored in the backend).
 * The primary use is to track content expiry times, and to determine which backend bytebin should
 * read from if the content isn't already cached in memory. It is also used for metrics.</p>
 *
 * <p>Backed by PostgreSQL via MyBatis.</p>
 */
public class ContentIndexDatabase implements AutoCloseable {

    private static final Logger LOGGER = LogManager.getLogger(ContentIndexDatabase.class);

    /**
     * Initialises the content index database. If the database is empty (no rows in the content
     * table), the index is rebuilt by scanning all storage backends.
     *
     * @param sqlSessionFactory the MyBatis session factory (already migrated by Flyway)
     * @param backends the storage backends to rebuild from if needed
     * @return the initialised database
     */
    public static ContentIndexDatabase initialise(SqlSessionFactory sqlSessionFactory, Collection<StorageBackend> backends) {
        ContentIndexDatabase database = new ContentIndexDatabase(sqlSessionFactory);

        // Check if the index is empty and rebuild from backends if so.
        // This replaces the old SQLite "does the file exist?" check.
        boolean empty;
        try (SqlSession session = sqlSessionFactory.openSession()) {
            ContentMapper mapper = session.getMapper(ContentMapper.class);
            // Quick check: if there are any expired entries at far-future time, the table has data.
            // More reliable: just try to get a count. Use getExpired with a far-future timestamp.
            // Actually, simplest: query for any single row.
            empty = mapper.getExpired(Long.MAX_VALUE).isEmpty()
                    && mapper.countByTypeAndBackend().isEmpty();
        }

        if (empty) {
            LOGGER.info("[INDEX DB] Database appears empty, rebuilding index from storage backends...");
            for (StorageBackend backend : backends) {
                try {
                    List<Content> metadata = backend.list().collect(Collectors.toList());
                    database.putAll(metadata);
                    LOGGER.info("[INDEX DB] Indexed {} entries from '{}' backend", metadata.size(), backend.getBackendId());
                } catch (Exception e) {
                    LOGGER.error("[INDEX DB] Error rebuilding index for " + backend.getBackendId() + " backend", e);
                }
            }
        }
        return database;
    }

    private static final Histogram.Child DURATION_HISTOGRAM_PUT = Metrics.DB_TRANSACTION_DURATION_HISTOGRAM.labels("put");
    private static final Histogram.Child DURATION_HISTOGRAM_GET = Metrics.DB_TRANSACTION_DURATION_HISTOGRAM.labels("get");
    private static final Histogram.Child DURATION_HISTOGRAM_PUT_ALL = Metrics.DB_TRANSACTION_DURATION_HISTOGRAM.labels("putAll");
    private static final Histogram.Child DURATION_HISTOGRAM_REMOVE = Metrics.DB_TRANSACTION_DURATION_HISTOGRAM.labels("remove");
    private static final Histogram.Child DURATION_HISTOGRAM_GET_EXPIRED = Metrics.DB_TRANSACTION_DURATION_HISTOGRAM.labels("getExpired");
    private static final Histogram.Child DURATION_HISTOGRAM_QUERY_METRICS = Metrics.DB_TRANSACTION_DURATION_HISTOGRAM.labels("queryMetrics");
    private static final Histogram.Child DURATION_HISTOGRAM_INCREMENT_READ_COUNT = Metrics.DB_TRANSACTION_DURATION_HISTOGRAM.labels("incrementReadCount");

    private final SqlSessionFactory sqlSessionFactory;

    public ContentIndexDatabase(SqlSessionFactory sqlSessionFactory) {
        this.sqlSessionFactory = sqlSessionFactory;
    }

    public void put(Content content) {
        try (Histogram.Timer ignored = DURATION_HISTOGRAM_PUT.startTimer();
             SqlSession session = this.sqlSessionFactory.openSession(true)) {
            ContentMapper mapper = session.getMapper(ContentMapper.class);
            mapper.upsert(content);
        } catch (Exception e) {
            LOGGER.error("[INDEX DB] Error performing sql operation", e);
            Metrics.DB_ERROR_COUNTER.labels("put").inc();
        }
    }

    public Content get(String key) {
        try (Histogram.Timer ignored = DURATION_HISTOGRAM_GET.startTimer();
             SqlSession session = this.sqlSessionFactory.openSession(true)) {
            ContentMapper mapper = session.getMapper(ContentMapper.class);
            return mapper.getByKey(key);
        } catch (Exception e) {
            LOGGER.error("[INDEX DB] Error performing sql operation", e);
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
        } catch (Exception e) {
            LOGGER.error("[INDEX DB] Error performing sql operation", e);
            Metrics.DB_ERROR_COUNTER.labels("putAll").inc();
        }
    }

    public void remove(String key) {
        try (Histogram.Timer ignored = DURATION_HISTOGRAM_REMOVE.startTimer();
             SqlSession session = this.sqlSessionFactory.openSession(true)) {
            ContentMapper mapper = session.getMapper(ContentMapper.class);
            mapper.deleteByKey(key);
        } catch (Exception e) {
            LOGGER.error("[INDEX DB] Error performing sql operation", e);
            Metrics.DB_ERROR_COUNTER.labels("remove").inc();
        }
    }

    public Collection<Content> getExpired() {
        try (Histogram.Timer ignored = DURATION_HISTOGRAM_GET_EXPIRED.startTimer();
             SqlSession session = this.sqlSessionFactory.openSession(true)) {
            ContentMapper mapper = session.getMapper(ContentMapper.class);
            return mapper.getExpired(System.currentTimeMillis());
        } catch (Exception e) {
            LOGGER.error("[INDEX DB] Error performing sql operation", e);
            Metrics.DB_ERROR_COUNTER.labels("getExpired").inc();
            return Collections.emptyList();
        }
    }

    /**
     * Atomically increments the read count for the given key in the database
     * and returns the new value.
     *
     * <p>This is safe to call from multiple application instances concurrently --
     * the increment is performed atomically by PostgreSQL using
     * {@code UPDATE ... SET read_count = read_count + 1 ... RETURNING read_count}.</p>
     *
     * @param key the content key
     * @return the new read count, or -1 if the key was not found
     */
    public int incrementReadCount(String key) {
        try (Histogram.Timer ignored = DURATION_HISTOGRAM_INCREMENT_READ_COUNT.startTimer();
             SqlSession session = this.sqlSessionFactory.openSession(true)) {
            ContentMapper mapper = session.getMapper(ContentMapper.class);
            Integer result = mapper.incrementReadCount(key);
            return result != null ? result : -1;
        } catch (Exception e) {
            LOGGER.error("[INDEX DB] Error performing sql operation", e);
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
            LOGGER.error("[INDEX DB] Error performing sql operation", e);
            Metrics.DB_ERROR_COUNTER.labels("queryMetrics").inc();
        }
    }

    @Override
    public void close() throws Exception {
        // The SqlSessionFactory doesn't need closing directly.
        // The underlying HikariCP DataSource is closed separately in Bytebin.close().
    }
}
