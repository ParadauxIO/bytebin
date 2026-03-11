package me.lucko.bytebin.ratelimit;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;

import java.util.concurrent.TimeUnit;

/**
 * Handles an exponential rate limit
 */
public class ExponentialRateLimiter implements RateLimiter {

    /** Rate limiter cache - allow x "actions" every x minutes, where x gets larger each time */
    private final LoadingCache<String, Counter> cache;

    /** The base period in milliseconds */
    private final long basePeriodMillis;
    /** The max period in milliseconds */
    private final long maxPeriodMillis;
    /** The multiplier to apply each period */
    private final double multiplier;
    /** The number of actions allowed in each period  */
    private final int actionsPerCycle;

    public ExponentialRateLimiter(int actionsPerCycle, int periodMins, double multiplier, int resetPeriodMins) {
        this.cache = Caffeine.newBuilder()
                .expireAfterAccess(resetPeriodMins, TimeUnit.MINUTES)
                .build(key -> new Counter());
        this.basePeriodMillis = TimeUnit.MINUTES.toMillis(periodMins);
        this.maxPeriodMillis = TimeUnit.MINUTES.toMillis(resetPeriodMins);
        this.multiplier = multiplier;
        this.actionsPerCycle = actionsPerCycle;
    }

    private final class Counter {
        private int count = 0;
        private long nextPeriodMillis = ExponentialRateLimiter.this.basePeriodMillis;
        private long periodEndMillis = 0;

        public synchronized boolean check() {
            return this.periodEndMillis != 0 && System.currentTimeMillis() < this.periodEndMillis;
        }

        public synchronized boolean checkAndIncrement() {
            boolean limited = check();
            if (!limited) {
                increment();
            }
            return limited;
        }

        public synchronized void increment() {
            this.count++;
            if (this.count >= ExponentialRateLimiter.this.actionsPerCycle) {
                this.count = 0;
                this.periodEndMillis = System.currentTimeMillis() + this.nextPeriodMillis;
                this.nextPeriodMillis = Math.min((long) (this.nextPeriodMillis * ExponentialRateLimiter.this.multiplier), ExponentialRateLimiter.this.maxPeriodMillis);
            }
        }
    }

    @Override
    public boolean check(String ipAddress) {
        //noinspection ConstantConditions
        return this.cache.get(ipAddress).check();
    }

    @Override
    public boolean checkAndIncrement(String ipAddress) {
        //noinspection ConstantConditions
        return this.cache.get(ipAddress).checkAndIncrement();
    }

    @Override
    public void increment(String ipAddress) {
        //noinspection ConstantConditions
        this.cache.get(ipAddress).increment();
    }
}
