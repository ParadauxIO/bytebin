package me.lucko.bytebin.http;

import io.jooby.Route;
import io.prometheus.client.Gauge;
import io.prometheus.client.Histogram;
import me.lucko.bytebin.util.Metrics;

public class MetricsFilter implements Route.Filter {
    private final Histogram.Child durationHistogram;
    private final Gauge.Child activeGauge;

    public MetricsFilter(String method) {
        this.durationHistogram = Metrics.HTTP_REQUEST_DURATION_HISTOGRAM.labels(method);
        this.activeGauge = Metrics.HTTP_REQUESTS_ACTIVE_GAUGE.labels(method);
    }

    @Override
    public Route.Handler apply(Route.Handler next) {
        return ctx -> {
            this.activeGauge.inc();
            Histogram.Timer timer = this.durationHistogram.startTimer();
            ctx.onComplete(endCtx -> {
                timer.observeDuration();
                this.activeGauge.dec();
            });
            return next.apply(ctx);
        };
    }
}
