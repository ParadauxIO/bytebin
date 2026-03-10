package me.lucko.bytebin.logging;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

public class HttpLogHandler extends AbstractAsyncLogHandler {
    private static final Logger LOGGER = LogManager.getLogger(HttpLogHandler.class);

    private final HttpClient client = HttpClient.newHttpClient();
    private final Gson gson = new GsonBuilder().serializeNulls().create();
    private final String uri;

    public HttpLogHandler(String uri, int flushIntervalSeconds) {
        super(flushIntervalSeconds);
        this.uri = uri;
    }

    @Override
    public void flush(List<Event> events) {
        try {
            String json = this.gson.toJson(events);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(this.uri))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            this.client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            LOGGER.error("Failed to send log data to HTTP endpoint", e);
        }
    }
}
