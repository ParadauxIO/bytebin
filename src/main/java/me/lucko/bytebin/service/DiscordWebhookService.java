package me.lucko.bytebin.service;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

/**
 * Service for sending messages to a Discord webhook.
 *
 * <p>Supports sending rich embed messages using Discord's webhook API.</p>
 */
public class DiscordWebhookService {

    private static final Logger LOGGER = LogManager.getLogger(DiscordWebhookService.class);

    private final HttpClient client = HttpClient.newHttpClient();
    private final Gson gson = new GsonBuilder().serializeNulls().create();
    private final String webhookUrl;

    public DiscordWebhookService(String webhookUrl) {
        this.webhookUrl = webhookUrl;
    }

    /**
     * Sends a message with embeds to the Discord webhook.
     *
     * @param embeds the list of embed objects to send
     */
    public void sendEmbeds(List<Map<String, Object>> embeds) {
        try {
            Map<String, Object> payload = Map.of("embeds", embeds);
            String json = this.gson.toJson(payload);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(this.webhookUrl))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<String> response = this.client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                LOGGER.info("[DISCORD] Successfully sent embed message to webhook");
            } else {
                LOGGER.warn("[DISCORD] Webhook returned status {}: {}", response.statusCode(), response.body());
            }
        } catch (Exception e) {
            LOGGER.error("[DISCORD] Failed to send embed message to webhook", e);
        }
    }
}
