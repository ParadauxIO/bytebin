package me.lucko.bytebin.content;

import com.github.benmanes.caffeine.cache.CacheLoader;
import com.google.common.collect.ImmutableMap;
import io.prometheus.client.Histogram;
import me.lucko.bytebin.content.storage.StorageBackend;
import me.lucko.bytebin.util.Metrics;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Function;

/**
 * Coordinates the storage of content in a storage backend.
 */
public class ContentStorageHandler implements CacheLoader<String, Content> {

    private static final Logger LOGGER = LogManager.getLogger(ContentStorageHandler.class);

    /** An index of all stored content */
    private final ContentIndexDatabase index;

    /** The backends in use for content storage */
    private final Map<String, StorageBackend> backends;

    /** The function used to select which backend to use for content storage */
    private final StorageBackendSelector backendSelector;

    /** The executor to use for i/o */
    private final ScheduledExecutorService executor;

    public ContentStorageHandler(ContentIndexDatabase contentIndex, Collection<StorageBackend> backends, StorageBackendSelector backendSelector, ScheduledExecutorService executor) {
        this.index = contentIndex;
        this.backends = backends.stream().collect(ImmutableMap.toImmutableMap(
                StorageBackend::getBackendId, Function.identity()
        ));
        this.backendSelector = backendSelector;
        this.executor = executor;
    }

    /**
     * Load content.
     *
     * @param key the key to load
     * @return the loaded content
     */
    @Override
    public Content load(String key) {
        // query the index to see if content with this key is stored
        Content metadata = this.index.get(key);
        if (metadata == null) {
            return Content.EMPTY_CONTENT;
        }

        // find the backend that the content is stored in
        String backendId = metadata.getBackendId();

        StorageBackend backend = this.backends.get(backendId);
        if (backend == null) {
            LOGGER.error("Unable to load " + key + " - no such backend '" + backendId + "'");
            Metrics.BACKEND_ERROR_COUNTER.labels(backendId, "load").inc();
            return Content.EMPTY_CONTENT;
        }

        // increment the read counter
        Metrics.BACKEND_READ_COUNTER.labels(backendId).inc();
        LOGGER.info("[STORAGE] Loading '" + key + "' from the '" + backendId + "' backend");

        // load the content from the backend
        try (Histogram.Timer ignored = Metrics.BACKEND_READ_DURATION_HISTOGRAM.labels(backendId).startTimer()) {
            Content content = backend.load(key);
            if (content != null) {
                return content;
            }
        } catch (Exception e) {
            LOGGER.warn("[STORAGE] Unable to load '" + key + "' from the '" + backendId + "' backend", e);
            Metrics.BACKEND_ERROR_COUNTER.labels(backendId, "load").inc();
        }

        return Content.EMPTY_CONTENT;
    }

    /**
     * Save content.
     *
     * @param content the content to save
     */
    public void save(Content content) {
        // select a backend to store the content in
        StorageBackend backend = this.backendSelector.select(content);
        String backendId = backend.getBackendId();

        // record which backend the content is going to be stored in, and write to the index
        content.setBackendId(backend.getBackendId());
        this.index.put(content);

        // increment the write counter
        Metrics.BACKEND_WRITE_COUNTER.labels(backendId).inc();

        // save to the backend
        try (Histogram.Timer ignored = Metrics.BACKEND_WRITE_DURATION_HISTOGRAM.labels(backendId).startTimer()) {
            backend.save(content);
        } catch (Exception e) {
            LOGGER.warn("[STORAGE] Unable to save '" + content.getKey() + "' to the '" + backendId + "' backend", e);
            Metrics.BACKEND_ERROR_COUNTER.labels(backendId, "save").inc();
        }
    }

    /**
     * Updates the index entry for the given content (e.g. after incrementing read count).
     *
     * @param content the content to update in the index
     */
    public void updateIndex(Content content) {
        this.index.put(content);
    }

    /**
     * Atomically increments the read count for the given key in the database
     * and returns the new value. This is safe across multiple application instances.
     *
     * @param key the content key
     * @return the new read count, or -1 if the key was not found
     */
    public int incrementReadCount(String key) {
        return this.index.incrementReadCount(key);
    }

    /**
     * Delete content.
     *
     * @param content the content to delete
     */
    public void delete(Content content) {
        String key = content.getKey();

        // find the backend that the content is stored in
        String backendId = content.getBackendId();
        StorageBackend backend = this.backends.get(backendId);
        if (backend == null) {
            LOGGER.error("[STORAGE] Unable to delete " + key + " - no such backend '" + backendId + "'");
            Metrics.BACKEND_ERROR_COUNTER.labels(backendId, "delete").inc();
            return;
        }

        Metrics.BACKEND_DELETE_COUNTER.labels(backendId).inc();

        // delete the data from the backend
        try (Histogram.Timer ignored = Metrics.BACKEND_DELETE_DURATION_HISTOGRAM.labels(backendId).startTimer()) {
            backend.delete(key);
        } catch (Exception e) {
            LOGGER.warn("[STORAGE] Unable to delete '" + key + "' from the '" + backend.getBackendId() + "' backend", e);
            Metrics.BACKEND_ERROR_COUNTER.labels(backendId, "delete").inc();
        }

        // remove the entry from the index
        this.index.remove(key);

        LOGGER.info("[STORAGE] Deleted '" + key + "' from the '" + backendId + "' backend");
    }

    /**
     * Invalidates/deletes any expired content and updates the metrics gauges
     */
    public void runInvalidationAndRecordMetrics() {
        // query the index for content which has expired
        Collection<Content> expired = this.index.getExpired();

        for (Content metadata : expired) {
            delete(metadata);
        }

        // update metrics
        this.index.recordMetrics();
    }

    /**
     * Bulk deletes the provided keys
     *
     * @param keys the keys to delete
     * @param force whether to sill attempt deletion if the content is not in the index
     * @return how many entries were actually deleted
     */
    public int bulkDelete(List<String> keys, boolean force) {
        int count = 0;
        for (String key : keys) {
            Content content = this.index.get(key);
            if (content == null) {
                if (force) {
                    for (StorageBackend backend : this.backends.values()) {
                        try {
                            backend.delete(key);
                        } catch (Exception e) {
                            // ignore
                        }
                    }
                }
                continue;
            }

            delete(content);
            count++;
        }

        // update metrics
        this.index.recordMetrics();

        return count;
    }

    public Executor getExecutor() {
        return this.executor;
    }
}
