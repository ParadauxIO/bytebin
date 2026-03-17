package me.lucko.bytebin.controller;

import io.jooby.Context;
import io.jooby.MediaType;
import io.jooby.Route;
import io.jooby.StatusCode;
import io.jooby.exception.StatusCodeException;
import me.lucko.bytebin.content.Content;
import me.lucko.bytebin.logging.LogHandler;
import me.lucko.bytebin.ratelimit.RateLimitHandler;
import me.lucko.bytebin.ratelimit.RateLimiter;
import me.lucko.bytebin.service.ContentLoader;
import me.lucko.bytebin.service.ContentService;
import me.lucko.bytebin.service.UsageEventService;
import me.lucko.bytebin.usage.UsageEvent;
import me.lucko.bytebin.util.ContentEncoding;
import me.lucko.bytebin.util.Gzip;
import me.lucko.bytebin.util.Metrics;
import me.lucko.bytebin.util.TokenGenerator;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Controller for GET /{id} requests - retrieves stored content.
 */
public final class ContentGetController implements Route.Handler {

    /** Logger instance */
    private static final Logger LOGGER = LogManager.getLogger(ContentGetController.class);

    private final BytebinServer server;
    private final LogHandler logHandler;
    private final RateLimiter rateLimiter;
    private final RateLimiter notFoundRateLimiter;
    private final RateLimitHandler rateLimitHandler;
    private final ContentLoader contentLoader;
    private final ContentService contentService;
    private final UsageEventService usageEventService;

    public ContentGetController(BytebinServer server, LogHandler logHandler, RateLimiter rateLimiter, RateLimiter notFoundRateLimiter, RateLimitHandler rateLimitHandler, ContentLoader contentLoader, ContentService contentService, UsageEventService usageEventService) {
        this.server = server;
        this.logHandler = logHandler;
        this.rateLimiter = rateLimiter;
        this.notFoundRateLimiter = notFoundRateLimiter;
        this.rateLimitHandler = rateLimitHandler;
        this.contentLoader = contentLoader;
        this.contentService = contentService;
        this.usageEventService = usageEventService;
    }

    @Override
    public CompletableFuture<byte[]> apply(@Nonnull Context ctx) {
        // get the requested path
        String path = ctx.path("id").value();
        if (path.trim().isEmpty() || TokenGenerator.INVALID_TOKEN_PATTERN.matcher(path).find()) {
            Metrics.recordRejectedRequest("GET", "invalid_path", ctx);
            throw new StatusCodeException(StatusCode.NOT_FOUND, "Invalid path");
        }

        // check rate limits
        RateLimitHandler.Result rateLimitResult = this.rateLimitHandler.getIpAddressAndCheckRateLimit(ctx, this.rateLimiter, "GET");
        String ipAddress = rateLimitResult.ipAddress();

        // get the encodings supported by the requester
        Set<String> acceptedEncoding = ContentEncoding.getAcceptedEncoding(ctx);

        // get the user agent & origin headers
        String userAgent = ctx.header("User-Agent").value("null");
        String origin = ctx.header("Origin").value("null");
        String host = ctx.getHostAndPort();
        Map<String, String> headers = ctx.headerMap();

        LOGGER.info("[REQUEST]\n" +
                "    key = " + path + "\n" +
                "    user agent = " + userAgent + "\n" +
                "    ip = " + ipAddress + "\n" +
                (origin.equals("null") ? "" : "    origin = " + origin + "\n") +
                "    host = " + host + "\n"
        );

        if (rateLimitResult.isRealUser()) {
            this.logHandler.logAttemptedGet(path, new LogHandler.User(userAgent, origin, host, ipAddress, headers));

            if (this.notFoundRateLimiter.check(rateLimitResult.ipAddress())) {
                Metrics.recordRejectedRequest("GET", "rate_limited_get_not_found", ctx);
                throw new StatusCodeException(StatusCode.TOO_MANY_REQUESTS, "Rate limit exceeded");
            }
        }

        // request the file from the cache async
        return this.contentLoader.get(path).handleAsync((content, throwable) -> {
            if (throwable != null || content == null || content.getKey() == null || content.getContent().length == 0) {
                if (throwable != null) {
                    LOGGER.warn("[REQUEST] Error loading content for key={}", path, throwable);
                } else {
                    LOGGER.debug("[REQUEST] Content not found for key={}", path);
                }
                if (rateLimitResult.isRealUser()) {
                    this.notFoundRateLimiter.increment(rateLimitResult.ipAddress());
                }
                Metrics.recordRejectedRequest("GET", "not_found", ctx);
                throw new StatusCodeException(StatusCode.NOT_FOUND, "Invalid path");
            }

            // check if content has expired
            if (content.getExpiry() != null && content.getExpiry().getTime() < System.currentTimeMillis()) {
                LOGGER.info("[REQUEST] Content '" + path + "' has expired, rejecting and scheduling deletion");
                this.contentLoader.invalidate(List.of(path));
                this.contentService.getExecutor().execute(() -> this.contentService.delete(content));
                Metrics.recordRejectedRequest("GET", "expired", ctx);
                throw new StatusCodeException(StatusCode.NOT_FOUND, "Invalid path");
            }

            if (rateLimitResult.isRealUser()) {
                Metrics.recordRequest("GET", ctx);
                this.logHandler.logGet(
                        path,
                        new LogHandler.User(userAgent, origin, host, ipAddress, headers),
                        new LogHandler.ContentInfo(content.getContentLength(), content.getContentType(), content.getExpiry())
                );
            }

            // record usage event
            try {
                UsageEvent event = UsageEventService.builderFromContext(ctx, "api_get")
                        .ipAddress(ipAddress)
                        .contentKey(path)
                        .contentType(content.getContentType())
                        .contentLength(content.getContentLength())
                        .contentEncoding(content.getEncoding())
                        .responseCode(200)
                        .hasApiKey(rateLimitResult.validApiKey())
                        .forwarded(rateLimitResult.forwarded())
                        .build();
                this.usageEventService.record(event);
            } catch (Exception e) {
                LOGGER.warn("[REQUEST] Error recording usage event for key={}", path, e);
            }

            ctx.setResponseHeader("Last-Modified", Instant.ofEpochMilli(content.getLastModified()));

            // always track read count; enforce max reads limit when applicable
            int reads = this.contentService.incrementReadCount(content.getKey());
            if (reads < 0) {
                reads = content.incrementAndGetReadCount();
            } else {
                content.setReadCount(reads);
            }
            if (content.hasReadLimit()) {
                ctx.setResponseHeader("Bytebin-Reads-Remaining", String.valueOf(Math.max(0, content.getMaxReads() - reads)));
                if (reads >= content.getMaxReads()) {
                    this.contentLoader.invalidate(List.of(path));
                    this.contentService.getExecutor().execute(() -> this.contentService.delete(content));
                    LOGGER.info("[REQUEST] Content '" + path + "' reached max reads (" + content.getMaxReads() + "), scheduled for deletion");
                }
            }

            if (content.isModifiable()) {
                ctx.setResponseHeader("Cache-Control", "public, no-cache, proxy-revalidate, no-transform");
            } else {
                ctx.setResponseHeader("Cache-Control", "public, max-age=604800, no-transform, immutable");
            }

            List<String> contentEncodingStrings = ContentEncoding.getContentEncoding(content.getEncoding());

            // requester supports the used content encoding, just serve as-is
            if (acceptedEncoding.contains("*") || acceptedEncoding.containsAll(contentEncodingStrings)) {
                ctx.setResponseHeader("Content-Encoding", content.getEncoding());
                ctx.setResponseType(MediaType.valueOf(content.getContentType()));
                return content.getContent();
            }

            LOGGER.warn("[REQUEST] Request for 'key = " + path + "' was made with incompatible Accept-Encoding headers! " +
                    "Content-Encoding = " + contentEncodingStrings + ", " +
                    "Accept-Encoding = " + acceptedEncoding);

            // if it's compressed using gzip, we will uncompress on the server side
            if (contentEncodingStrings.size() == 1 && contentEncodingStrings.get(0).equals(ContentEncoding.GZIP)) {
                byte[] uncompressed;
                try {
                    uncompressed = Gzip.decompress(content.getContent());
                } catch (IOException e) {
                    LOGGER.warn("[REQUEST] Failed to decompress gzip content for key={}", path, e);
                    throw new StatusCodeException(StatusCode.NOT_FOUND, "Unable to uncompress data");
                }

                ctx.setResponseType(MediaType.valueOf(content.getContentType()));
                return uncompressed;
            }

            throw new StatusCodeException(StatusCode.NOT_ACCEPTABLE, "Accept-Encoding \"" + ctx.header("Accept-Encoding").value("") + "\" does not contain Content-Encoding \"" + content.getEncoding() + "\"");
        });
    }
}
