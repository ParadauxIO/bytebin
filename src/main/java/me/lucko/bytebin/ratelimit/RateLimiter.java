package me.lucko.bytebin.ratelimit;

/**
 * Handles a rate limit
 */
public interface RateLimiter {

    boolean check(String ipAddress);

    boolean checkAndIncrement(String ipAddress);

    void increment(String ipAddress);

}
