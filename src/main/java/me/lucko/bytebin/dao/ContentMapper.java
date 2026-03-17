package me.lucko.bytebin.dao;

import me.lucko.bytebin.content.Content;
import me.lucko.bytebin.content.ContentStorageMetric;
import me.lucko.bytebin.content.DateEpochMillisTypeHandler;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * MyBatis mapper interface (DAO) for the content index table.
 *
 * <p>Uses annotation-based SQL mappings instead of XML.</p>
 */
public interface ContentMapper {

    /**
     * Gets content metadata by key.
     *
     * @param key the content key
     * @return the content, or null if not found
     */
    @Select("SELECT key, content_type, expiry, last_modified, encoding, backend_id, " +
            "content_length, max_reads, read_count " +
            "FROM content WHERE key = #{key}")
    @Results(id = "contentResultMap", value = {
            @Result(property = "key", column = "key", id = true),
            @Result(property = "contentType", column = "content_type"),
            @Result(property = "expiry", column = "expiry", typeHandler = DateEpochMillisTypeHandler.class),
            @Result(property = "lastModified", column = "last_modified"),
            @Result(property = "encoding", column = "encoding"),
            @Result(property = "backendId", column = "backend_id"),
            @Result(property = "contentLength", column = "content_length"),
            @Result(property = "maxReads", column = "max_reads"),
            @Result(property = "readCount", column = "read_count")
    })
    Content getByKey(@Param("key") String key);

    /**
     * Inserts or updates content metadata (upsert).
     *
     * @param content the content to upsert
     */
    @Insert("INSERT INTO content (key, content_type, expiry, last_modified, encoding, backend_id, " +
            "content_length, max_reads, read_count) " +
            "VALUES (#{key}, #{contentType}, " +
            "#{expiry, typeHandler=me.lucko.bytebin.content.DateEpochMillisTypeHandler}, " +
            "#{lastModified}, #{encoding}, #{backendId}, " +
            "#{contentLength}, #{maxReads}, #{readCount}) " +
            "ON CONFLICT (key) DO UPDATE SET " +
            "content_type = EXCLUDED.content_type, " +
            "expiry = EXCLUDED.expiry, " +
            "last_modified = EXCLUDED.last_modified, " +
            "encoding = EXCLUDED.encoding, " +
            "backend_id = EXCLUDED.backend_id, " +
            "content_length = EXCLUDED.content_length, " +
            "max_reads = EXCLUDED.max_reads, " +
            "read_count = EXCLUDED.read_count")
    void upsert(Content content);

    /**
     * Inserts a single content entry (used during index rebuild).
     *
     * @param content the content to insert
     */
    @Insert("INSERT INTO content (key, content_type, expiry, last_modified, encoding, backend_id, " +
            "content_length, max_reads, read_count) " +
            "VALUES (#{key}, #{contentType}, " +
            "#{expiry, typeHandler=me.lucko.bytebin.content.DateEpochMillisTypeHandler}, " +
            "#{lastModified}, #{encoding}, #{backendId}, " +
            "#{contentLength}, #{maxReads}, #{readCount}) " +
            "ON CONFLICT (key) DO NOTHING")
    void insert(Content content);

    /**
     * Deletes content metadata by key.
     *
     * @param key the content key
     */
    @Delete("DELETE FROM content WHERE key = #{key}")
    void deleteByKey(@Param("key") String key);

    /**
     * Gets all expired content entries.
     *
     * @param nowMillis the current time in epoch milliseconds
     * @return list of expired content entries
     */
    @Select("SELECT key, content_type, expiry, last_modified, encoding, backend_id, " +
            "content_length, max_reads, read_count " +
            "FROM content WHERE expiry IS NOT NULL AND expiry < #{nowMillis}")
    @Results({
            @Result(property = "key", column = "key", id = true),
            @Result(property = "contentType", column = "content_type"),
            @Result(property = "expiry", column = "expiry", typeHandler = DateEpochMillisTypeHandler.class),
            @Result(property = "lastModified", column = "last_modified"),
            @Result(property = "encoding", column = "encoding"),
            @Result(property = "backendId", column = "backend_id"),
            @Result(property = "contentLength", column = "content_length"),
            @Result(property = "maxReads", column = "max_reads"),
            @Result(property = "readCount", column = "read_count")
    })
    List<Content> getExpired(@Param("nowMillis") long nowMillis);

    /**
     * Atomically increments the read count for the given key and returns the new value.
     *
     * @param key the content key
     * @return the new read count after incrementing, or null if key not found
     */
    @Select("UPDATE content SET read_count = read_count + 1 WHERE key = #{key} RETURNING read_count")
    Integer incrementReadCount(@Param("key") String key);

    /**
     * Gets the count of content entries grouped by content type and backend.
     *
     * @return list of storage metric entries with count
     */
    @Select("SELECT content_type, backend_id, count(*) AS value FROM content GROUP BY content_type, backend_id")
    @Results({
            @Result(property = "contentType", column = "content_type"),
            @Result(property = "backendId", column = "backend_id"),
            @Result(property = "value", column = "value")
    })
    List<ContentStorageMetric> countByTypeAndBackend();

    /**
     * Gets the total content length grouped by content type and backend.
     *
     * @return list of storage metric entries with total size
     */
    @Select("SELECT content_type, backend_id, COALESCE(sum(content_length), 0) AS value " +
            "FROM content GROUP BY content_type, backend_id")
    @Results({
            @Result(property = "contentType", column = "content_type"),
            @Result(property = "backendId", column = "backend_id"),
            @Result(property = "value", column = "value")
    })
    List<ContentStorageMetric> sumSizeByTypeAndBackend();

    /**
     * Lists all content entries, ordered by last modified descending, with pagination.
     *
     * @param limit  max number of entries to return
     * @param offset number of entries to skip
     * @return paginated list of content entries
     */
    @Select("SELECT key, content_type, expiry, last_modified, encoding, backend_id, " +
            "content_length, max_reads, read_count " +
            "FROM content ORDER BY last_modified DESC LIMIT #{limit} OFFSET #{offset}")
    @ResultMap("contentResultMap")
    List<Content> listAll(@Param("limit") int limit, @Param("offset") int offset);

    /**
     * Counts the total number of content entries.
     *
     * @return total count
     */
    @Select("SELECT COUNT(*) FROM content")
    long countAll();

    /**
     * Gets the total storage bytes used across all content.
     *
     * @return total bytes
     */
    @Select("SELECT COALESCE(SUM(content_length), 0) FROM content")
    long sumStorageBytes();
}
