package me.lucko.bytebin.ratelimit;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Handles a rate limit
 */
public class SimpleRateLimiter implements RateLimiter {
    /** Rate limiter cache - allow x "actions" every x minutes */
    private final LoadingCache<String, AtomicInteger> rateLimiter;
    /** The number of actions allowed in each period  */
    private final int actionsPerCycle;

    public SimpleRateLimiter(int actionsPerCycle, int periodMins) {
        this.rateLimiter = Caffeine.newBuilder()
                .expireAfterWrite(periodMins, TimeUnit.MINUTES)
                .build(key -> new AtomicInteger(0));
        this.actionsPerCycle = actionsPerCycle;
    }

    @Override
    public boolean check(String ipAddress) {
        //noinspection ConstantConditions
        return this.rateLimiter.get(ipAddress).get() > this.actionsPerCycle;
    }

    @Override
    public boolean checkAndIncrement(String ipAddress) {
        //noinspection ConstantConditions
        return this.rateLimiter.get(ipAddress).incrementAndGet() > this.actionsPerCycle;
    }

    @Override
    public void increment(String ipAddress) {
        //noinspection ConstantConditions
        this.rateLimiter.get(ipAddress).incrementAndGet();
    }
}
