package me.lucko.bytebin.controller;

import io.jooby.Jooby;
import io.jooby.MediaType;
import java.io.InputStream;
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
import me.lucko.bytebin.logging.LogHandler;
import me.lucko.bytebin.ratelimit.RateLimitHandler;
import me.lucko.bytebin.ratelimit.RateLimiter;
import me.lucko.bytebin.service.ContentLoader;
import me.lucko.bytebin.service.ContentService;
import me.lucko.bytebin.service.UsageEventService;
import me.lucko.bytebin.usage.UsageEvent;
import me.lucko.bytebin.util.ExpiryHandler;
import me.lucko.bytebin.util.Metrics;
import me.lucko.bytebin.util.TokenGenerator;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletionException;

/**
 * The Jooby web application that wires together all controllers and middleware.
 */
public class BytebinServer extends Jooby {

    /** Logger instance */
    private static final Logger LOGGER = LogManager.getLogger(BytebinServer.class);

    public BytebinServer(
            ContentService contentService,
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
            Path localAssetPath,
            UsageEventService usageEventService,
            AdminController adminController
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
                StatusCodeException sce = (StatusCodeException) rootCause;
                LOGGER.debug("StatusCodeException: status={} message={} path={}", sce.getStatusCode(), sce.getMessage(), ctx.getRequestPath());
                ctx.setResponseCode(sce.getStatusCode())
                        .setResponseType(MediaType.TEXT)
                        .send(rootCause.getMessage());
            } else {
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
        // record UI visit events for asset requests
        routes(() -> {
            use((Route.Filter) next -> ctx -> {
                ctx.onComplete(context -> {
                    try {
                        String path = context.getRequestPath();
                        if (path.equals("/") || path.endsWith(".html")) {
                            String ipAddress = context.header("x-real-ip").valueOrNull();
                            if (ipAddress == null) {
                                ipAddress = context.getRemoteAddress();
                            }
                            UsageEvent event = UsageEventService.builderFromContext(context, "ui_visit")
                                    .ipAddress(ipAddress)
                                    .responseCode(context.getResponseCode().value())
                                    .build();
                            usageEventService.record(event);
                        }
                    } catch (Exception e) {
                        LOGGER.warn("Error recording UI visit usage event for path={}", context.getRequestPath(), e);
                    }
                });
                return next.apply(ctx);
            });
            assets("/*", new AssetHandler(localFiles, classPathFiles, fourOhFour).setMaxAge(Duration.ofDays(1)));
        });

        // admin portal - redirect /admin to /admin/index.html (served by asset handler)
        final AdminController admin = adminController;
        if (admin != null) {
            get("/admin", ctx -> {
                ctx.setResponseType(MediaType.html);
                try (InputStream is = Bytebin.class.getClassLoader().getResourceAsStream("www/admin/index.html")) {
                    if (is == null) throw new StatusCodeException(StatusCode.NOT_FOUND, "Not found");
                    return is.readAllBytes();
                }
            });
            get("/admin/api/config", admin::handleConfig);
            routes(() -> {
                use(new CorsHandler(new Cors()
                        .setUseCredentials(false)
                        .setMaxAge(Duration.ofDays(1))
                        .setMethods("GET", "DELETE")
                        .setHeaders("Authorization", "Content-Type")));
                use((Route.Filter) next -> ctx -> {
                    admin.requireAuth(ctx);
                    return next.apply(ctx);
                });
                get("/admin/api/overview", admin::handleOverview);
                get("/admin/api/pastes", admin::handleListPastes);
                delete("/admin/api/pastes/{key:[a-zA-Z0-9]+}", admin::handleDeletePaste);
                get("/admin/api/usage/events", admin::handleListUsageEvents);
                get("/admin/api/usage/stats", admin::handleUsageStats);
                get("/admin/api/usage/hourly", admin::handleHourlyStats);
            });
        }

        // healthcheck endpoint
        get("/health", ctx -> {
            ctx.setResponseType(MediaType.JSON);
            ctx.setResponseHeader("Cache-Control", "no-cache");
            return "{\"status\":\"ok\"}";
        });

        // metrics endpoint
        if (metrics) {
            get("/metrics", new MetricsController());
        }

        // viewer page for content
        get("/view/{id:[a-zA-Z0-9]+}", new ContentViewController(usageEventService));

        // define route handlers
        routes(() -> {
            use(new CorsHandler(new Cors()
                    .setUseCredentials(false)
                    .setMaxAge(Duration.ofDays(1))
                    .setMethods("POST", "PUT")
                    .setHeaders("Content-Type", "Accept", "Origin", "Content-Encoding", "Allow-Modification", "Bytebin-Api-Key", "Bytebin-Forwarded-For", "Bytebin-Max-Reads", "Bytebin-Expiry")));

            Route.Handler postController = new MetricsFilter("POST").then(new ContentPostController(this, logHandler, postRateLimiter, rateLimitHandler, contentService, contentLoader, contentTokenGenerator, maxContentLength, expiryHandler, hostAliases, usageEventService));
            post("/post", postController);
            put("/post", postController);
        });

        routes(() -> {
            use(new CorsHandler(new Cors()
                    .setUseCredentials(false)
                    .setMaxAge(Duration.ofDays(1))
                    .setMethods("GET", "PUT")
                    .setHeaders("Content-Type", "Accept", "Origin", "Content-Encoding", "Authorization", "Bytebin-Api-Key", "Bytebin-Forwarded-For")));

            get("/{id:[a-zA-Z0-9]+}", new MetricsFilter("GET").then(new ContentGetController(this, logHandler, readRateLimiter, readNotFoundRateLimiter, rateLimitHandler, contentLoader, contentService, usageEventService)));
            put("/{id:[a-zA-Z0-9]+}", new MetricsFilter("PUT").then(new ContentUpdateController(this, logHandler, putRateLimiter, rateLimitHandler, contentService, contentLoader, maxContentLength, expiryHandler, usageEventService)));
        });
    }

}
