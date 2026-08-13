package me.lucko.bytebin.dao;

import me.lucko.bytebin.usage.UsageEvent;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * MyBatis mapper interface (DAO) for the usage_events table.
 *
 * <p>Uses annotation-based SQL mappings instead of XML.</p>
 */
public interface UsageEventMapper {

    /**
     * Inserts a single usage event.
     *
     * @param event the event to insert
     */
    @Insert("INSERT INTO usage_events (event_type, timestamp, ip_address, user_agent, origin, host, referer, " +
            "accept_language, content_key, http_method, content_type, content_length, " +
            "content_encoding, response_code, has_api_key, has_custom_expiry, " +
            "has_max_reads, has_allow_modification, is_forwarded) " +
            "VALUES (#{eventType}, #{timestamp}, #{ipAddress}, #{userAgent}, #{origin}, #{host}, #{referer}, " +
            "#{acceptLanguage}, #{contentKey}, #{httpMethod}, #{contentType}, #{contentLength}, " +
            "#{contentEncoding}, #{responseCode}, #{hasApiKey}, #{hasCustomExpiry}, " +
            "#{hasMaxReads}, #{hasAllowModification}, #{forwarded})")
    void insert(UsageEvent event);

    /**
     * Inserts a batch of usage events.
     *
     * @param events the events to insert
     */
    @Insert("<script>" +
            "INSERT INTO usage_events (event_type, timestamp, ip_address, user_agent, origin, host, referer, " +
            "accept_language, content_key, http_method, content_type, content_length, " +
            "content_encoding, response_code, has_api_key, has_custom_expiry, " +
            "has_max_reads, has_allow_modification, is_forwarded) VALUES " +
            "<foreach collection='collection' item='event' separator=','>" +
            "(#{event.eventType}, #{event.timestamp}, #{event.ipAddress}, #{event.userAgent}, #{event.origin}, " +
            "#{event.host}, #{event.referer}, #{event.acceptLanguage}, #{event.contentKey}, #{event.httpMethod}, " +
            "#{event.contentType}, #{event.contentLength}, #{event.contentEncoding}, #{event.responseCode}, " +
            "#{event.hasApiKey}, #{event.hasCustomExpiry}, #{event.hasMaxReads}, #{event.hasAllowModification}, " +
            "#{event.forwarded})" +
            "</foreach>" +
            "</script>")
    void insertBatch(Collection<UsageEvent> events);

    /**
     * Deletes up to {@code limit} events older than the given cutoff.
     *
     * <p>Deleting a bounded batch rather than issuing one unbounded
     * {@code DELETE ... WHERE timestamp &lt; ?} keeps each statement short-lived —
     * the table can hold millions of rows and the database is shared with other
     * applications.</p>
     *
     * <p>Matching on {@code ctid} rather than {@code id} is deliberate: the
     * planner resolves it with a tid scan, where an {@code id IN (...)} subquery
     * makes it sequentially scan the whole table on every batch (measured at
     * 254ms vs 1ms per batch against a 1M-row table). Rows here are insert-only,
     * so their ctids are stable.</p>
     *
     * @param cutoffMillis events with a timestamp before this are eligible
     * @param limit the maximum number of rows to delete in this batch
     * @return the number of rows deleted
     */
    @Delete("DELETE FROM usage_events WHERE ctid IN (" +
            "SELECT ctid FROM usage_events WHERE timestamp < #{cutoffMillis} ORDER BY id LIMIT #{limit}" +
            ")")
    int deleteOlderThan(@Param("cutoffMillis") long cutoffMillis, @Param("limit") int limit);

    /**
     * Counts events grouped by event_type within a time range.
     *
     * @param sinceMillis the start timestamp (epoch millis, inclusive)
     * @param untilMillis the end timestamp (epoch millis, exclusive)
     * @return a list of maps with keys "event_type" and "count"
     */
    @Select("SELECT event_type, COUNT(*) AS count FROM usage_events " +
            "WHERE timestamp >= #{sinceMillis} AND timestamp < #{untilMillis} " +
            "GROUP BY event_type ORDER BY count DESC")
    List<Map<String, Object>> countByEventType(@Param("sinceMillis") long sinceMillis, @Param("untilMillis") long untilMillis);

    /**
     * Counts unique IP addresses within a time range.
     *
     * @param sinceMillis the start timestamp (epoch millis, inclusive)
     * @param untilMillis the end timestamp (epoch millis, exclusive)
     * @return the number of unique IPs
     */
    @Select("SELECT COUNT(DISTINCT ip_address) FROM usage_events " +
            "WHERE timestamp >= #{sinceMillis} AND timestamp < #{untilMillis}")
    long countUniqueIps(@Param("sinceMillis") long sinceMillis, @Param("untilMillis") long untilMillis);

    /**
     * Gets total content bytes posted (sum of content_length for api_post events) within a time range.
     *
     * @param sinceMillis the start timestamp (epoch millis, inclusive)
     * @param untilMillis the end timestamp (epoch millis, exclusive)
     * @return total bytes posted
     */
    @Select("SELECT COALESCE(SUM(content_length), 0) FROM usage_events " +
            "WHERE event_type = 'api_post' AND timestamp >= #{sinceMillis} AND timestamp < #{untilMillis}")
    long sumContentBytesPosted(@Param("sinceMillis") long sinceMillis, @Param("untilMillis") long untilMillis);

    /**
     * Gets the total number of events within a time range.
     *
     * @param sinceMillis the start timestamp (epoch millis, inclusive)
     * @param untilMillis the end timestamp (epoch millis, exclusive)
     * @return the total event count
     */
    @Select("SELECT COUNT(*) FROM usage_events " +
            "WHERE timestamp >= #{sinceMillis} AND timestamp < #{untilMillis}")
    long countTotal(@Param("sinceMillis") long sinceMillis, @Param("untilMillis") long untilMillis);

    /**
     * Gets the top user agents by request count within a time range.
     *
     * @param sinceMillis the start timestamp (epoch millis, inclusive)
     * @param untilMillis the end timestamp (epoch millis, exclusive)
     * @param limit the max number of results
     * @return a list of maps with keys "user_agent" and "count"
     */
    @Select("SELECT COALESCE(user_agent, 'unknown') AS user_agent, COUNT(*) AS count FROM usage_events " +
            "WHERE timestamp >= #{sinceMillis} AND timestamp < #{untilMillis} " +
            "GROUP BY user_agent ORDER BY count DESC LIMIT #{limit}")
    List<Map<String, Object>> topUserAgents(@Param("sinceMillis") long sinceMillis, @Param("untilMillis") long untilMillis, @Param("limit") int limit);

    /**
     * Lists recent usage events with optional event type filtering, newest first.
     *
     * @param limit     max number of events to return
     * @param offset    number of events to skip
     * @param eventType event type filter, or null for all types
     * @return paginated list of event rows
     */
    @Select("<script>" +
            "SELECT id, event_type, timestamp, ip_address, user_agent, origin, host, referer, " +
            "accept_language, content_key, http_method, content_type, content_length, " +
            "content_encoding, response_code, has_api_key, has_custom_expiry, " +
            "has_max_reads, has_allow_modification, is_forwarded " +
            "FROM usage_events " +
            "<if test='eventType != null'>WHERE event_type = #{eventType} </if>" +
            "ORDER BY timestamp DESC LIMIT #{limit} OFFSET #{offset}" +
            "</script>")
    List<Map<String, Object>> listRecent(@Param("limit") int limit, @Param("offset") int offset, @Param("eventType") String eventType);

    /**
     * Counts usage events, optionally filtered by event type.
     *
     * @param eventType event type filter, or null for all types
     * @return the count
     */
    @Select("<script>" +
            "SELECT COUNT(*) FROM usage_events " +
            "<if test='eventType != null'>WHERE event_type = #{eventType}</if>" +
            "</script>")
    long countEvents(@Param("eventType") String eventType);

    /**
     * Gets event counts grouped by hour and event type within a time range.
     *
     * @param sinceMillis start timestamp (epoch millis, inclusive)
     * @param untilMillis end timestamp (epoch millis, exclusive)
     * @return list of rows with hour_millis, event_type, count
     */
    @Select("SELECT (timestamp / 3600000) * 3600000 AS hour_millis, event_type, COUNT(*) AS count " +
            "FROM usage_events WHERE timestamp >= #{sinceMillis} AND timestamp < #{untilMillis} " +
            "GROUP BY hour_millis, event_type ORDER BY hour_millis ASC")
    List<Map<String, Object>> hourlyStats(@Param("sinceMillis") long sinceMillis, @Param("untilMillis") long untilMillis);
}
