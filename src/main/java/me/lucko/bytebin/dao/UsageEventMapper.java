package me.lucko.bytebin.dao;

import me.lucko.bytebin.usage.UsageEvent;
import org.apache.ibatis.annotations.Insert;

import java.util.Collection;

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
}
