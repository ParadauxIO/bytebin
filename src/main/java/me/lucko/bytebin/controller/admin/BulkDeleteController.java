package me.lucko.bytebin.controller.admin;

import com.google.gson.Gson;
import io.jooby.Context;
import io.jooby.Route;
import io.jooby.StatusCode;
import io.jooby.exception.StatusCodeException;
import me.lucko.bytebin.controller.BytebinServer;
import me.lucko.bytebin.service.ContentLoader;
import me.lucko.bytebin.service.ContentService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Controller for POST /admin/bulkdelete - bulk deletes content by keys.
 */
public final class BulkDeleteController implements Route.Handler {

    private static final String HEADER_API_KEY = "Bytebin-Api-Key";

    /** Logger instance */
    private static final Logger LOGGER = LogManager.getLogger(BulkDeleteController.class);

    private final BytebinServer server;
    private final ContentService contentService;
    private final ContentLoader contentLoader;
    private final Set<String> apiKeys;

    public BulkDeleteController(BytebinServer server, ContentService contentService, ContentLoader contentLoader, Set<String> apiKeys) {
        this.server = server;
        this.contentService = contentService;
        this.contentLoader = contentLoader;
        this.apiKeys = apiKeys;
    }

    @Override
    public CompletableFuture<Integer> apply(@Nonnull Context ctx) {
        String apiKey = ctx.header(HEADER_API_KEY).value("");
        if (apiKey.isEmpty() || !this.apiKeys.contains(apiKey)) {
            LOGGER.warn("[BULK DELETE] Unauthorized access attempt from ip={}", ctx.getRemoteAddress());
            throw new StatusCodeException(StatusCode.UNAUTHORIZED, "API key is invalid");
        }

        List<String> list = Arrays.asList(new Gson().fromJson(ctx.body().value(""), String[].class));

        if (list.isEmpty()) {
            throw new StatusCodeException(StatusCode.BAD_REQUEST, "Missing content");
        }

        String ipAddress = ctx.header("x-real-ip").valueOrNull();
        if (ipAddress == null) {
            ipAddress = ctx.getRemoteAddress();
        }
        String origin = ctx.header("Origin").valueOrNull();

        boolean force = ctx.query("force").booleanValue(false);

        LOGGER.info("[BULK DELETE]\n" +
                "    user agent = " + ctx.header("User-Agent").value("null") + "\n" +
                "    ip = " + ipAddress + "\n" +
                (origin == null ? "" : "    origin = " + origin + "\n") +
                "    keys = " + list + "\n" +
                "    force = " + force + "\n"
        );

        return CompletableFuture.supplyAsync(() -> {
            int deleted = this.contentService.bulkDelete(list, force);
            this.contentLoader.invalidate(list);
            LOGGER.info("[BULK DELETE] Successfully deleted " + deleted + " entries");
            return deleted;
        });
    }

}
