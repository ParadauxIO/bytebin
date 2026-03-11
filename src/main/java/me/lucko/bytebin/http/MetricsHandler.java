package me.lucko.bytebin.http;

import io.jooby.Context;
import io.jooby.Route;
import io.jooby.StatusCode;
import io.jooby.exception.StatusCodeException;
import io.prometheus.client.CollectorRegistry;
import io.prometheus.client.exporter.common.TextFormat;

import javax.annotation.Nonnull;
import java.io.OutputStreamWriter;

public final class MetricsHandler implements Route.Handler {

    @Override
    public Context apply(@Nonnull Context ctx) throws Exception {
        // deny requests via the reverse proxy
        if (ctx.header("X-Forwarded-For").isPresent()) {
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
