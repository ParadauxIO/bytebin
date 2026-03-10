package me.lucko.bytebin.content;

import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * MyBatis mapper interface for the content index table.
 */
public interface ContentMapper {

    /**
     * Gets content metadata by key.
     *
     * @param key the content key
     * @return the content, or null if not found
     */
    Content getByKey(@Param("key") String key);

    /**
     * Inserts or updates content metadata (upsert).
     *
     * @param content the content to upsert
     */
    void upsert(Content content);

    /**
     * Inserts a single content entry (used during index rebuild).
     *
     * @param content the content to insert
     */
    void insert(Content content);

    /**
     * Deletes content metadata by key.
     *
     * @param key the content key
     */
    void deleteByKey(@Param("key") String key);

    /**
     * Gets all expired content entries.
     *
     * @param nowMillis the current time in epoch milliseconds
     * @return list of expired content entries
     */
    List<Content> getExpired(@Param("nowMillis") long nowMillis);

    /**
     * Atomically increments the read count for the given key and returns the new value.
     *
     * @param key the content key
     * @return the new read count after incrementing, or null if key not found
     */
    Integer incrementReadCount(@Param("key") String key);

    /**
     * Gets the count of content entries grouped by content type and backend.
     *
     * @return list of storage metric entries with count
     */
    List<ContentStorageMetric> countByTypeAndBackend();

    /**
     * Gets the total content length grouped by content type and backend.
     *
     * @return list of storage metric entries with total size
     */
    List<ContentStorageMetric> sumSizeByTypeAndBackend();
}
