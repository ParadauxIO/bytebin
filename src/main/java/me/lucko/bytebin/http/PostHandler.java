package me.lucko.bytebin.http;

import io.jooby.Context;
import io.jooby.MediaType;
import io.jooby.Route;
import io.jooby.SneakyThrows;
import io.jooby.StatusCode;
import io.jooby.exception.StatusCodeException;
import me.lucko.bytebin.content.Content;
import me.lucko.bytebin.content.ContentLoader;
import me.lucko.bytebin.content.ContentStorageHandler;
import me.lucko.bytebin.logging.LogHandler;
import me.lucko.bytebin.ratelimit.RateLimitHandler;
import me.lucko.bytebin.ratelimit.RateLimiter;
import me.lucko.bytebin.usage.UsageEvent;
import me.lucko.bytebin.usage.UsageEventCollector;
import me.lucko.bytebin.util.ContentEncoding;
import me.lucko.bytebin.util.ExpiryHandler;
import me.lucko.bytebin.util.Gzip;
import me.lucko.bytebin.util.Metrics;
import me.lucko.bytebin.util.TokenGenerator;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nonnull;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public final class PostHandler implements Route.Handler {

    /** Logger instance */
    private static final Logger LOGGER = LogManager.getLogger(PostHandler.class);

    private final BytebinServer server;
    private final LogHandler logHandler;
    private final RateLimiter rateLimiter;
    private final RateLimitHandler rateLimitHandler;

    private final ContentStorageHandler storageHandler;
    private final ContentLoader contentLoader;
    private final TokenGenerator contentTokenGenerator;
    private final TokenGenerator authKeyTokenGenerator;
    private final long maxContentLength;
    private final ExpiryHandler expiryHandler;
    private final Map<String, String> hostAliases;
    private final UsageEventCollector usageEventCollector;

    public PostHandler(BytebinServer server, LogHandler logHandler, RateLimiter rateLimiter, RateLimitHandler rateLimitHandler, ContentStorageHandler storageHandler, ContentLoader contentLoader, TokenGenerator contentTokenGenerator, long maxContentLength, ExpiryHandler expiryHandler, Map<String, String> hostAliases, UsageEventCollector usageEventCollector) {
        this.server = server;
        this.logHandler = logHandler;
        this.rateLimiter = rateLimiter;
        this.rateLimitHandler = rateLimitHandler;
        this.storageHandler = storageHandler;
        this.contentLoader = contentLoader;
        this.contentTokenGenerator = contentTokenGenerator;
        this.authKeyTokenGenerator = new TokenGenerator(32);
        this.maxContentLength = maxContentLength;
        this.expiryHandler = expiryHandler;
        this.hostAliases = hostAliases;
        this.usageEventCollector = usageEventCollector;
    }

    @Override
    public String apply(@Nonnull Context ctx) {
        byte[] content = getBodyAsByteArray(ctx, (int) this.maxContentLength);

        // ensure something was actually posted
        if (content.length == 0) {
            Metrics.recordRejectedRequest("POST", "missing_content", ctx);
            throw new StatusCodeException(StatusCode.BAD_REQUEST, "Missing content");
        }

        // check rate limits
        RateLimitHandler.Result rateLimitResult = this.rateLimitHandler.getIpAddressAndCheckRateLimit(ctx, this.rateLimiter, "POST");
        String ipAddress = rateLimitResult.ipAddress();

        // determine the content type
        String contentType = ctx.header("Content-Type").value("text/plain");

        // generate a key
        String key = this.contentTokenGenerator.generate();

        // get the content encodings
        // https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/Content-Encoding
        List<String> encodings = ContentEncoding.getContentEncoding(ctx.header("Content-Encoding").valueOrNull());

        // get the user agent & origin headers
        String userAgent = ctx.header("User-Agent").value("null");
        String origin = ctx.header("Origin").value("null");
        String host = ctx.getHostAndPort();
        Map<String, String> headers = ctx.headerMap();

        Date expiry = this.expiryHandler.getExpiry(userAgent, origin, host);

        // check for custom expiry header (value in minutes)
        long customExpiryMinutes = ctx.header("Bytebin-Expiry").longValue(-1);
        if (customExpiryMinutes > 0) {
            expiry = new Date(System.currentTimeMillis() + customExpiryMinutes * 60 * 1000);
        }

        // default expiry to 30 days if not set
        if (expiry == null) {
            expiry = new Date(System.currentTimeMillis() + 30L * 24 * 60 * 60 * 1000);
        }

        final Date finalExpiry = expiry;

        // check for custom max reads header
        int maxReads = ctx.header("Bytebin-Max-Reads").intValue(-1);

        // check max content length
        if (content.length > this.maxContentLength) {
            Metrics.recordRejectedRequest("POST", "content_too_large", ctx);
            throw new StatusCodeException(StatusCode.REQUEST_ENTITY_TOO_LARGE, "Content too large");
        }

        // check for our custom Allow-Modification header
        boolean allowModifications = ctx.header("Allow-Modification").booleanValue(false);
        String authKey;
        if (allowModifications) {
            authKey = this.authKeyTokenGenerator.generate();
        } else {
            authKey = null;
        }

        LOGGER.info("[POST]\n" +
                "    key = " + key + "\n" +
                "    type = " + contentType + "\n" +
                "    user agent = " + userAgent + "\n" +
                "    ip = " + ipAddress + "\n" +
                (origin.equals("null") ? "" : "    origin = " + origin + "\n") +
                "    host = " + host + "\n" +
                "    content size = " + String.format("%,d", content.length / 1024) + " KB\n" +
                "    encoding = " + encodings.toString() + "\n" +
                (maxReads > 0 ? "    max reads = " + maxReads + "\n" : "") +
                (customExpiryMinutes > 0 ? "    custom expiry = " + customExpiryMinutes + " mins\n" : "")
        );

        // metrics
        if (rateLimitResult.isRealUser()) {
            String metricsLabel = Metrics.getMetricsLabel(ctx);
            Metrics.recordRequest("POST", metricsLabel);
            Metrics.HTTP_POST_CONTENT_SIZE_HISTOGRAM.labels(metricsLabel).observe(content.length);

            this.logHandler.logPost(
                    key,
                    new LogHandler.User(userAgent, origin, host, ipAddress, headers),
                    new LogHandler.ContentInfo(content.length, contentType, finalExpiry)
            );
        }

        // record usage event
        try {
            UsageEvent event = UsageEventCollector.builderFromContext(ctx, "api_post")
                    .ipAddress(ipAddress)
                    .contentKey(key)
                    .contentType(contentType)
                    .contentLength(content.length)
                    .contentEncoding(String.join(",", encodings))
                    .responseCode(201)
                    .hasApiKey(rateLimitResult.validApiKey())
                    .hasCustomExpiry(customExpiryMinutes > 0)
                    .hasMaxReads(maxReads > 0)
                    .hasAllowModification(allowModifications)
                    .forwarded(rateLimitResult.forwarded())
                    .build();
            this.usageEventCollector.record(event);
        } catch (Exception ignored) {
            // never let metrics collection break the actual request
        }

        // record the content in the cache
        CompletableFuture<Content> future = new CompletableFuture<>();
        this.contentLoader.put(key, future);

        // check whether the content should be compressed by bytebin before saving
        boolean compressServerSide = encodings.isEmpty();
        if (compressServerSide) {
            encodings.add(ContentEncoding.GZIP);
        }

        String encoding = String.join(",", encodings);
        this.storageHandler.getExecutor().execute(() -> {
            byte[] buf = content;
            if (compressServerSide) {
                buf = Gzip.compress(buf);
            }

            // add directly to the cache
            // it's quite likely that the file will be requested only a few seconds after it is uploaded
            Content c = new Content(key, contentType, finalExpiry, System.currentTimeMillis(), authKey != null, authKey, encoding, buf, maxReads);
            future.complete(c);

            try {
                this.storageHandler.save(c);
            } finally {
                c.getSaveFuture().complete(null);
            }
        });

        // return the url location as plain content
        ctx.setResponseCode(StatusCode.CREATED);

        if (allowModifications) {
            ctx.setResponseHeader("Modification-Key", authKey);
        }

        if (ctx.getMethod().equals("PUT")) {
            // PUT: return the URL where the content can be accessed
            host = this.hostAliases.getOrDefault(host, host);
            String location = "https://" + host + "/" + key;

            ctx.setResponseHeader("Location", location);
            ctx.setResponseType(MediaType.TEXT);
            return location + "\n";
        } else {
            // POST: return the key
            ctx.setResponseHeader("Location", key);
            ctx.setResponseType(MediaType.JSON);
            return "{\"key\":\"" + key + "\"}";
        }
    }

    static byte[] getBodyAsByteArray(Context ctx, int maxSize) {
        int declaredSize = ctx.header("Content-Length").intValue(16384);

        if (declaredSize > maxSize) {
            Metrics.recordRejectedRequest("POST", "content_too_large", ctx);
            throw new StatusCodeException(StatusCode.REQUEST_ENTITY_TOO_LARGE, "Content too large");
        }

        try (InputStream stream = ctx.body().stream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(declaredSize);
            int len;
            byte[] buffer = new byte[16384];
            while ((len = stream.read(buffer)) != -1) {
                out.write(buffer, 0, len);
            }
            return out.toByteArray();
        } catch (IOException x) {
            throw SneakyThrows.propagate(x);
        }
    }

}
