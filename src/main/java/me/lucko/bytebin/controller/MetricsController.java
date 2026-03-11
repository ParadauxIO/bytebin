package me.lucko.bytebin.controller;

import io.jooby.Context;
import io.jooby.Route;
import io.jooby.StatusCode;
import io.jooby.exception.StatusCodeException;
import io.prometheus.client.CollectorRegistry;
import io.prometheus.client.exporter.common.TextFormat;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nonnull;
import java.io.OutputStreamWriter;

/**
 * Controller for GET /metrics - exposes Prometheus metrics.
 */
public final class MetricsController implements Route.Handler {

    /** Logger instance */
    private static final Logger LOGGER = LogManager.getLogger(MetricsController.class);

    @Override
    public Context apply(@Nonnull Context ctx) throws Exception {
        // deny requests via the reverse proxy
        if (ctx.header("X-Forwarded-For").isPresent()) {
            LOGGER.warn("[METRICS] Unauthorized metrics access attempt from forwarded ip={}", ctx.header("X-Forwarded-For").value(""));
            throw new StatusCodeException(StatusCode.UNAUTHORIZED);
        }

        String contentType = TextFormat.chooseContentType(ctx.header("Accept").valueOrNull());
        ctx.setResponseHeader("Content-Type", contentType);

        try (OutputStreamWriter writer = new OutputStreamWriter(ctx.responseStream())) {
            TextFormat.writeFormat(contentType, writer, CollectorRegistry.defaultRegistry.metricFamilySamples());
        }

        return ctx;
    }

}
