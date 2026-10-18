package me.lucko.bytebin.ratelimit;

import com.google.common.collect.ImmutableSet;
import io.jooby.Context;
import io.jooby.StatusCode;
import io.jooby.exception.StatusCodeException;
import me.lucko.bytebin.util.Metrics;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Collection;
import java.util.Set;

/**
 * Handles rate limit checking for the application.
 *
 * Trusted server-side applications making requests to bytebin on
 * behalf of other clients can authenticate using an API key and provide
 * the client's IP address using an HTTP header. In this case, the client IP
 * address will be used for rate limiting purposes instead.
 */
public final class RateLimitHandler {

    /** Logger instance */
    private static final Logger LOGGER = LogManager.getLogger(RateLimitHandler.class);

    private static final String HEADER_FORWARDED_IP = "Bytebin-Forwarded-For";
    private static final String HEADER_API_KEY = "Bytebin-Api-Key";

    private final Set<String> apiKeys;

    public RateLimitHandler(Collection<String> apiKeys) {
        this.apiKeys = ImmutableSet.copyOf(apiKeys);
    }

    public boolean isValidApiKey(Context ctx) {
        String apiKey = ctx.header(HEADER_API_KEY).value("");
        if (!apiKey.isEmpty()) {
            if (!this.apiKeys.contains(apiKey)) {
                LOGGER.warn("Invalid API key attempt from ip={} path={}", ctx.getRemoteAddress(), ctx.getRequestPath());
                throw new StatusCodeException(StatusCode.UNAUTHORIZED, "API key is invalid");
            }

            return true;
        }
        return false;
    }

    public Result getIpAddressAndCheckRateLimit(Context ctx, RateLimiter limiter, String method) {
        // get the connection IP address according to cloudflare, fallback to
        // the remote address
        String ipAddress = ctx.header("x-real-ip").valueOrNull();
        if (ipAddress == null) {
            ipAddress = ctx.getRemoteAddress();
        }

        // if an API key has been specified, replace the IP address with the one
        // specified by the forwarded-for header.
        boolean validApiKey = isValidApiKey(ctx);
        boolean forwarded = false;
        if (validApiKey) {
            String originalIp = ctx.header(HEADER_FORWARDED_IP).valueOrNull();
            if (originalIp == null) {
                return new Result(ipAddress, true, false);
            }

            ipAddress = originalIp;
            forwarded = true;
        }

        // check rate limits
        if (limiter.checkAndIncrement(ipAddress)) {
            LOGGER.warn("Rate limit exceeded: ip={} method={} path={} forwarded={}", ipAddress, method, ctx.getRequestPath(), forwarded);
            Metrics.recordRejectedRequest(method, "rate_limited", ctx);
            throw new StatusCodeException(StatusCode.TOO_MANY_REQUESTS, "Rate limit exceeded");
        }

        return new Result(ipAddress, validApiKey, forwarded);
    }

    public record Result(String ipAddress, boolean validApiKey, boolean forwarded) {

        public boolean isRealUser() {
            // if API key not provided, assume real user
            // if API key provided but forwarded IP known, assume real user
            // else, assume not real user
            return !this.validApiKey || this.forwarded;
        }
    }


}
