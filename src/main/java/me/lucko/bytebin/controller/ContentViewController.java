package me.lucko.bytebin.controller;

import io.jooby.Context;
import io.jooby.MediaType;
import io.jooby.Route;
import io.jooby.StatusCode;
import io.jooby.exception.StatusCodeException;
import me.lucko.bytebin.service.UsageEventService;
import me.lucko.bytebin.usage.UsageEvent;
import me.lucko.bytebin.util.Metrics;
import me.lucko.bytebin.util.TokenGenerator;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;

/**
 * Controller for GET /view/{id} requests - serves the viewer HTML page.
 *
 * <p>The viewer page loads content client-side via the standard GET /{id}
 * endpoint and renders it with syntax highlighting, media previews, etc.</p>
 */
public final class ContentViewController implements Route.Handler {

    private static final Logger LOGGER = LogManager.getLogger(ContentViewController.class);

    /** The viewer HTML page content, loaded once from classpath */
    private static final byte[] VIEWER_PAGE;

    static {
        byte[] page;
        try (var is = ContentViewController.class.getResourceAsStream("/www/view.html")) {
            page = is != null ? is.readAllBytes() : "<html><body>Viewer not found</body></html>".getBytes();
        } catch (IOException e) {
            page = "<html><body>Error loading viewer</body></html>".getBytes();
        }
        VIEWER_PAGE = page;
    }

    private final UsageEventService usageEventService;

    public ContentViewController(UsageEventService usageEventService) {
        this.usageEventService = usageEventService;
    }

    @Override
    public CompletableFuture<byte[]> apply(@Nonnull Context ctx) {
        String path = ctx.path("id").value();
        if (path.trim().isEmpty() || TokenGenerator.INVALID_TOKEN_PATTERN.matcher(path).find()) {
            Metrics.recordRejectedRequest("GET", "invalid_path", ctx);
            throw new StatusCodeException(StatusCode.NOT_FOUND, "Invalid path");
        }

        // record a key_visit event for the UI viewer (client will separately fetch raw content via GET /{id})
        try {
            String ipAddress = ctx.header("x-real-ip").valueOrNull();
            if (ipAddress == null) {
                ipAddress = ctx.getRemoteAddress();
            }
            UsageEvent event = UsageEventService.builderFromContext(ctx, "key_visit")
                    .ipAddress(ipAddress)
                    .contentKey(path)
                    .responseCode(200)
                    .build();
            this.usageEventService.record(event);
        } catch (Exception e) {
            LOGGER.warn("[REQUEST] Error recording usage event for key={}", path, e);
        }

        ctx.setResponseType(MediaType.html);
        ctx.setResponseHeader("Cache-Control", "public, max-age=3600");
        return CompletableFuture.completedFuture(VIEWER_PAGE);
    }
}
