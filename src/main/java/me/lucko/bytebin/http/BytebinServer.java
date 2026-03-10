package me.lucko.bytebin.http;

import io.jooby.Jooby;
import io.jooby.MediaType;
import io.jooby.ReactiveSupport;
import io.jooby.Route;
import io.jooby.RouterOptions;
import io.jooby.StatusCode;
import io.jooby.exception.StatusCodeException;
import io.jooby.handler.AssetHandler;
import io.jooby.handler.AssetSource;
import io.jooby.handler.Cors;
import io.jooby.handler.CorsHandler;
import me.lucko.bytebin.Bytebin;
import me.lucko.bytebin.content.ContentLoader;
import me.lucko.bytebin.content.ContentStorageHandler;
import me.lucko.bytebin.http.admin.BulkDeleteHandler;
import me.lucko.bytebin.logging.LogHandler;
import me.lucko.bytebin.ratelimit.RateLimitHandler;
import me.lucko.bytebin.ratelimit.RateLimiter;
import me.lucko.bytebin.util.ExpiryHandler;
import me.lucko.bytebin.util.Metrics;
import me.lucko.bytebin.util.TokenGenerator;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionException;

public class BytebinServer extends Jooby {

    /** Logger instance */
    private static final Logger LOGGER = LogManager.getLogger(BytebinServer.class);

    public BytebinServer(
            ContentStorageHandler storageHandler,
            ContentLoader contentLoader,
            LogHandler logHandler,
            boolean metrics,
            RateLimitHandler rateLimitHandler,
            RateLimiter postRateLimiter,
            RateLimiter putRateLimiter,
            RateLimiter readRateLimiter,
            RateLimiter readNotFoundRateLimiter,
            TokenGenerator contentTokenGenerator,
            long maxContentLength,
            ExpiryHandler expiryHandler,
            Map<String, String> hostAliases,
            Set<String> adminApiKeys,
            Path localAssetPath
    ) {
        setRouterOptions(new RouterOptions().setTrustProxy(true));

        use(ReactiveSupport.concurrent());

        // catch all errors & just return some generic error message
        error((ctx, cause, code) -> {
            Throwable rootCause = cause;
            while (rootCause instanceof CompletionException && rootCause.getCause() != null) {
                rootCause = rootCause.getCause();
            }

            if (rootCause instanceof StatusCodeException) {
                // handle expected errors
                ctx.setResponseCode(((StatusCodeException) rootCause).getStatusCode())
                        .setResponseType(MediaType.TEXT)
                        .send(rootCause.getMessage());
            } else {
                // handle unexpected errors: log stack trace and send a generic response
                LOGGER.error("Error thrown by handler", cause);
                Metrics.UNCAUGHT_ERROR_COUNTER.labels(cause.getClass().getSimpleName()).inc();
                ctx.setResponseCode(StatusCode.NOT_FOUND)
                        .setResponseType(MediaType.TEXT)
                        .send("Invalid path");
            }
        });

        AssetSource localFiles = localAssetPath != null ? AssetSource.create(localAssetPath) : path -> null;
        AssetSource classPathFiles = AssetSource.create(Bytebin.class.getClassLoader(), "/www/");
        AssetSource fourOhFour = path -> { throw new StatusCodeException(StatusCode.NOT_FOUND, "Not found"); };

        // serve index page or favicon, otherwise 404
        assets("/*", new AssetHandler(localFiles, classPathFiles, fourOhFour).setMaxAge(Duration.ofDays(1)));

        // healthcheck endpoint
        get("/health", ctx -> {
            ctx.setResponseHeader("Cache-Control", "no-cache");
            return "{\"status\":\"ok\"}";
        });

        // metrics endpoint
        if (metrics) {
            get("/metrics", new MetricsHandler());
        }

        // define route handlers
        routes(() -> {
            use(new CorsHandler(new Cors()
                    .setUseCredentials(false)
                    .setMaxAge(Duration.ofDays(1))
                    .setMethods("POST", "PUT")
                    .setHeaders("Content-Type", "Accept", "Origin", "Content-Encoding", "Allow-Modification", "Bytebin-Api-Key", "Bytebin-Forwarded-For", "Bytebin-Max-Reads", "Bytebin-Expiry")));

            Route.Handler postHandler = new MetricsFilter("POST").then(new PostHandler(this, logHandler, postRateLimiter, rateLimitHandler, storageHandler, contentLoader, contentTokenGenerator, maxContentLength, expiryHandler, hostAliases));
            post("/post", postHandler);
            put("/post", postHandler);
        });

        routes(() -> {
            use(new CorsHandler(new Cors()
                    .setUseCredentials(false)
                    .setMaxAge(Duration.ofDays(1))
                    .setMethods("GET", "PUT")
                    .setHeaders("Content-Type", "Accept", "Origin", "Content-Encoding", "Authorization", "Bytebin-Api-Key", "Bytebin-Forwarded-For")));

            get("/{id:[a-zA-Z0-9]+}", new MetricsFilter("GET").then(new GetHandler(this, logHandler, readRateLimiter, readNotFoundRateLimiter, rateLimitHandler, contentLoader, storageHandler)));
            put("/{id:[a-zA-Z0-9]+}", new MetricsFilter("PUT").then(new UpdateHandler(this, logHandler, putRateLimiter, rateLimitHandler, storageHandler, contentLoader, maxContentLength, expiryHandler)));
        });

        routes(() -> {
            post("/admin/bulkdelete", new BulkDeleteHandler(this, storageHandler, contentLoader, adminApiKeys));
        });
    }

}
