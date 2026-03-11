package me.lucko.bytebin.service;

import com.github.benmanes.caffeine.cache.AsyncLoadingCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Weigher;
import me.lucko.bytebin.content.Content;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Responsible for loading content, optionally with caching.
 *
 * <p>Part of the service layer, sits between controllers and the content service.</p>
 */
public interface ContentLoader {

    /** Logger instance */
    Logger LOGGER = LogManager.getLogger(ContentLoader.class);

    static ContentLoader create(ContentService contentService, int cacheTimeMins, int cacheMaxSizeMb) {
        if (cacheTimeMins > 0 && cacheMaxSizeMb > 0) {
            LOGGER.info("[CACHE] Using cached content loader: expiry={}min, maxSize={}MB", cacheTimeMins, cacheMaxSizeMb);
            return new CachedContentLoader(contentService, cacheTimeMins, cacheMaxSizeMb);
        } else {
            LOGGER.info("[CACHE] Using direct content loader (no cache)");
            return new DirectContentLoader(contentService);
        }
    }

    /**
     * Adds a newly submitted entry to the loader cache.
     *
     * @param key the key
     * @param future a future encapsulating the content
     */
    void put(String key, CompletableFuture<Content> future);

    /**
     * Gets an entry from the loader.
     *
     * @param key the key
     * @return a future encapsulating the content
     */
    CompletableFuture<? extends Content> get(String key);

    /**
     * Invalidates any cache for the given keys.
     *
     * @param keys the keys
     */
    void invalidate(List<String> keys);

    /**
     * A {@link ContentLoader} backed by a cache.
     */
    final class CachedContentLoader implements ContentLoader {
        private final AsyncLoadingCache<String, Content> cache;

        CachedContentLoader(ContentService contentService, int cacheTimeMins, int cacheMaxSizeMb) {
            this.cache = Caffeine.newBuilder()
                    .executor(contentService.getExecutor())
                    .expireAfterAccess(cacheTimeMins, TimeUnit.MINUTES)
                    .maximumWeight(cacheMaxSizeMb * Content.MEGABYTE_LENGTH)
                    .weigher((Weigher<String, Content>) (path, content) -> content.getContent().length)
                    .buildAsync(contentService);
        }

        @Override
        public void put(String key, CompletableFuture<Content> future) {
            this.cache.put(key, future);
        }

        @Override
        public CompletableFuture<Content> get(String key) {
            return this.cache.get(key);
        }

        @Override
        public void invalidate(List<String> keys) {
            this.cache.synchronous().invalidateAll(keys);
        }
    }

    /**
     * A {@link ContentLoader} that makes requests directly to the content service with no caching.
     */
    final class DirectContentLoader implements ContentLoader {
        private final ContentService contentService;
        private final Map<String, CompletableFuture<Content>> saveInProgress = new ConcurrentHashMap<>();

        DirectContentLoader(ContentService contentService) {
            this.contentService = contentService;
        }

        @Override
        public void put(String key, CompletableFuture<Content> future) {
            if (future.isDone() && future.join().getSaveFuture().isDone()) {
                return;
            }

            // record in map while the save is in progress, then immediately remove
            this.saveInProgress.put(key, future);
            future.thenCompose(Content::getSaveFuture).thenRun(() -> this.saveInProgress.remove(key));
        }

        @Override
        public CompletableFuture<? extends Content> get(String key) {
            CompletableFuture<Content> saveInProgressFuture = this.saveInProgress.get(key);
            if (saveInProgressFuture != null) {
                return saveInProgressFuture;
            }

            try {
                return this.contentService.asyncLoad(key, this.contentService.getExecutor());
            } catch (Exception e) {
                LOGGER.warn("[CACHE] Error loading content directly for key={}", key, e);
                throw new RuntimeException(e);
            }
        }

        @Override
        public void invalidate(List<String> keys) {
            for (String key : keys) {
                this.saveInProgress.remove(key);
            }
        }
    }

}
