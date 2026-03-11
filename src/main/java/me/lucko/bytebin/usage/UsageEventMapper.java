package me.lucko.bytebin.usage;

import java.util.Collection;

/**
 * MyBatis mapper interface for the usage_events table.
 */
public interface UsageEventMapper {

    /**
     * Inserts a single usage event.
     *
     * @param event the event to insert
     */
    void insert(UsageEvent event);

    /**
     * Inserts a batch of usage events.
     *
     * @param events the events to insert
     */
    void insertBatch(Collection<UsageEvent> events);
}
