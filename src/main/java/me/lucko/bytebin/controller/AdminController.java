package me.lucko.bytebin.controller;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.jooby.Context;
import io.jooby.MediaType;
import io.jooby.StatusCode;
import io.jooby.exception.StatusCodeException;
import me.lucko.bytebin.content.Content;
import me.lucko.bytebin.dao.ContentDao;
import me.lucko.bytebin.dao.UsageEventDao;
import me.lucko.bytebin.service.ContentService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.RSAPublicKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Admin API controller.
 *
 * <p>Provides admin-only endpoints secured by Keycloak OIDC JWT validation.
 * JWT tokens are validated locally using the Keycloak JWKS endpoint, with the
 * public key set cached in memory and refreshed hourly.</p>
 */
public class AdminController {

    private static final Logger LOGGER = LogManager.getLogger(AdminController.class);

    private final ContentDao contentDao;
    private final ContentService contentService;
    private final UsageEventDao usageEventDao;
    private final String keycloakUrl;
    private final String keycloakRealm;
    private final String clientId;
    private final String requiredRole;
    private final Gson gson = new Gson();

    // JWKS public key cache
    private final AtomicReference<Map<String, PublicKey>> jwksCache = new AtomicReference<>(new HashMap<>());
    private volatile Instant jwksCacheTime = Instant.EPOCH;
    private static final Duration JWKS_CACHE_TTL = Duration.ofHours(1);

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public AdminController(
            ContentDao contentDao,
            ContentService contentService,
            UsageEventDao usageEventDao,
            String keycloakUrl,
            String keycloakRealm,
            String clientId,
            String requiredRole
    ) {
        this.contentDao = contentDao;
        this.contentService = contentService;
        this.usageEventDao = usageEventDao;
        this.keycloakUrl = keycloakUrl;
        this.keycloakRealm = keycloakRealm;
        this.clientId = clientId;
        this.requiredRole = requiredRole;
    }

    /**
     * GET /admin/api/config - Returns OIDC client configuration for the admin frontend.
     * This endpoint does NOT require authentication.
     */
    public Object handleConfig(Context ctx) {
        ctx.setResponseType(MediaType.JSON);
        ctx.setResponseHeader("Cache-Control", "no-cache");
        JsonObject config = new JsonObject();
        config.addProperty("keycloakUrl", keycloakUrl);
        config.addProperty("realm", keycloakRealm);
        config.addProperty("clientId", clientId);
        return gson.toJson(config);
    }

    /**
     * Validates the JWT from the Authorization header and throws if invalid.
     * Call this at the start of every protected handler.
     */
    public void requireAuth(Context ctx) {
        String authHeader = ctx.header("Authorization").valueOrNull();
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw new StatusCodeException(StatusCode.UNAUTHORIZED, "Missing or invalid Authorization header");
        }
        validateJwt(authHeader.substring(7));
    }

    /**
     * GET /admin/api/overview - Dashboard statistics.
     */
    public Object handleOverview(Context ctx) {
        ctx.setResponseType(MediaType.JSON);

        long now = System.currentTimeMillis();
        long last24h = now - Duration.ofHours(24).toMillis();

        long totalPastes = contentDao.countAll();
        long totalStorageBytes = contentDao.sumStorageBytes();
        long events24h = usageEventDao.countTotal(last24h, now);
        long uniqueIps24h = usageEventDao.countUniqueIps(last24h, now);
        long bytesPosted24h = usageEventDao.sumContentBytesPosted(last24h, now);
        List<Map<String, Object>> eventsByType = usageEventDao.countByEventType(last24h, now);

        JsonObject result = new JsonObject();
        result.addProperty("totalPastes", totalPastes);
        result.addProperty("totalStorageBytes", totalStorageBytes);
        result.addProperty("events24h", events24h);
        result.addProperty("uniqueIps24h", uniqueIps24h);
        result.addProperty("bytesPosted24h", bytesPosted24h);
        result.add("eventsByType", eventTypeListToJson(eventsByType));

        return gson.toJson(result);
    }

    /**
     * GET /admin/api/pastes?page=0&size=50 - Paginated list of all pastes.
     */
    public Object handleListPastes(Context ctx) {
        ctx.setResponseType(MediaType.JSON);

        int page = ctx.query("page").intValue(0);
        int size = Math.min(ctx.query("size").intValue(50), 200);
        int offset = page * size;

        long total = contentDao.countAll();
        List<Content> items = contentDao.listAll(size, offset);

        JsonObject result = new JsonObject();
        result.addProperty("total", total);
        result.addProperty("page", page);
        result.addProperty("size", size);

        JsonArray arr = new JsonArray();
        for (Content c : items) {
            JsonObject item = new JsonObject();
            item.addProperty("key", c.getKey());
            item.addProperty("contentType", c.getContentType());
            if (c.getExpiry() != null) {
                item.addProperty("expiry", c.getExpiry().getTime());
            } else {
                item.add("expiry", JsonNull.INSTANCE);
            }
            item.addProperty("lastModified", c.getLastModified());
            item.addProperty("encoding", c.getEncoding());
            item.addProperty("backendId", c.getBackendId());
            item.addProperty("contentLength", c.getContentLength());
            item.addProperty("maxReads", c.getMaxReads());
            item.addProperty("readCount", c.getReadCount());
            arr.add(item);
        }
        result.add("items", arr);

        return gson.toJson(result);
    }

    /**
     * DELETE /admin/api/pastes/{key} - Permanently deletes a paste.
     */
    public Object handleDeletePaste(Context ctx) {
        ctx.setResponseType(MediaType.JSON);

        String key = ctx.path("key").value();
        Content content = contentDao.get(key);
        if (content == null) {
            throw new StatusCodeException(StatusCode.NOT_FOUND, "Paste not found: " + key);
        }

        contentService.delete(content);
        LOGGER.info("[ADMIN] Deleted paste '{}'", key);

        JsonObject result = new JsonObject();
        result.addProperty("success", true);
        result.addProperty("key", key);
        return gson.toJson(result);
    }

    /**
     * GET /admin/api/usage/events?page=0&size=50&type= - Paginated usage events.
     */
    public Object handleListUsageEvents(Context ctx) {
        ctx.setResponseType(MediaType.JSON);

        int page = ctx.query("page").intValue(0);
        int size = Math.min(ctx.query("size").intValue(50), 200);
        String eventType = ctx.query("type").valueOrNull();
        int offset = page * size;

        long total = usageEventDao.countEvents(eventType);
        List<Map<String, Object>> items = usageEventDao.listRecent(size, offset, eventType);

        JsonObject result = new JsonObject();
        result.addProperty("total", total);
        result.addProperty("page", page);
        result.addProperty("size", size);
        result.add("items", gson.toJsonTree(items));

        return gson.toJson(result);
    }

    /**
     * GET /admin/api/usage/stats?since=24h - Aggregate usage statistics for a period.
     * The {@code since} parameter accepts: 1h, 24h, 7d, 30d.
     */
    public Object handleUsageStats(Context ctx) {
        ctx.setResponseType(MediaType.JSON);

        String since = ctx.query("since").value("24h");
        long[] range = parseSince(since);

        long total = usageEventDao.countTotal(range[0], range[1]);
        long uniqueIps = usageEventDao.countUniqueIps(range[0], range[1]);
        long bytesPosted = usageEventDao.sumContentBytesPosted(range[0], range[1]);
        List<Map<String, Object>> eventsByType = usageEventDao.countByEventType(range[0], range[1]);
        List<Map<String, Object>> topAgents = usageEventDao.topUserAgents(range[0], range[1], 10);

        JsonObject result = new JsonObject();
        result.addProperty("period", since);
        result.addProperty("total", total);
        result.addProperty("uniqueIps", uniqueIps);
        result.addProperty("bytesPosted", bytesPosted);
        result.add("eventsByType", eventTypeListToJson(eventsByType));

        JsonArray agentsArr = new JsonArray();
        for (Map<String, Object> row : topAgents) {
            JsonObject item = new JsonObject();
            item.addProperty("userAgent", String.valueOf(row.get("user_agent")));
            item.addProperty("count", ((Number) row.get("count")).longValue());
            agentsArr.add(item);
        }
        result.add("topUserAgents", agentsArr);

        return gson.toJson(result);
    }

    /**
     * GET /admin/api/usage/hourly?since=24h - Hourly event breakdown for a period.
     */
    public Object handleHourlyStats(Context ctx) {
        ctx.setResponseType(MediaType.JSON);

        String since = ctx.query("since").value("24h");
        long[] range = parseSince(since);

        List<Map<String, Object>> data = usageEventDao.hourlyStats(range[0], range[1]);

        JsonObject result = new JsonObject();
        result.addProperty("period", since);
        result.add("data", gson.toJsonTree(data));

        return gson.toJson(result);
    }

    // -------------------------------------------------------------------------
    // JWT Validation
    // -------------------------------------------------------------------------

    private void validateJwt(String token) {
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            throw new StatusCodeException(StatusCode.UNAUTHORIZED, "Invalid JWT format");
        }

        try {
            Base64.Decoder decoder = Base64.getUrlDecoder();

            String headerJson = new String(decoder.decode(padBase64(parts[0])), StandardCharsets.UTF_8);
            JsonObject header = JsonParser.parseString(headerJson).getAsJsonObject();
            String kid = header.has("kid") ? header.get("kid").getAsString() : null;
            String alg = header.has("alg") ? header.get("alg").getAsString() : "RS256";

            String payloadJson = new String(decoder.decode(padBase64(parts[1])), StandardCharsets.UTF_8);
            JsonObject payload = JsonParser.parseString(payloadJson).getAsJsonObject();

            // Validate expiry
            if (!payload.has("exp") || Instant.now().getEpochSecond() > payload.get("exp").getAsLong()) {
                throw new StatusCodeException(StatusCode.UNAUTHORIZED, "Token has expired");
            }

            // Validate issuer
            String expectedIss = keycloakUrl + "/realms/" + keycloakRealm;
            String iss = payload.has("iss") ? payload.get("iss").getAsString() : "";
            if (!expectedIss.equals(iss)) {
                throw new StatusCodeException(StatusCode.UNAUTHORIZED, "Invalid token issuer");
            }

            // Validate audience / authorized party.
            // Keycloak sets azp to the requesting client on all token types.
            // aud may be a string or array and often includes "account" rather than
            // the client id, so we accept if EITHER azp == clientId OR aud contains clientId.
            boolean audienceValid = false;
            if (payload.has("azp") && clientId.equals(payload.get("azp").getAsString())) {
                audienceValid = true;
            } else if (payload.has("aud")) {
                JsonElement audElem = payload.get("aud");
                if (audElem.isJsonArray()) {
                    for (JsonElement a : audElem.getAsJsonArray()) {
                        if (clientId.equals(a.getAsString())) {
                            audienceValid = true;
                            break;
                        }
                    }
                } else {
                    audienceValid = clientId.equals(audElem.getAsString());
                }
            }
            if (!audienceValid) {
                throw new StatusCodeException(StatusCode.UNAUTHORIZED, "Token was not issued for this application");
            }

            // Validate signature
            PublicKey publicKey = resolvePublicKey(kid);
            if (publicKey == null) {
                throw new StatusCodeException(StatusCode.UNAUTHORIZED, "Unknown signing key");
            }

            String javaAlg = switch (alg) {
                case "RS256" -> "SHA256withRSA";
                case "RS384" -> "SHA384withRSA";
                case "RS512" -> "SHA512withRSA";
                default -> throw new StatusCodeException(StatusCode.UNAUTHORIZED, "Unsupported algorithm: " + alg);
            };

            Signature sig = Signature.getInstance(javaAlg);
            sig.initVerify(publicKey);
            sig.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.UTF_8));
            if (!sig.verify(decoder.decode(padBase64(parts[2])))) {
                throw new StatusCodeException(StatusCode.UNAUTHORIZED, "Invalid token signature");
            }

            // Validate required role (if configured)
            if (requiredRole != null && !requiredRole.isBlank()) {
                boolean hasRole = false;
                if (payload.has("realm_access")) {
                    JsonArray roles = payload.getAsJsonObject("realm_access").getAsJsonArray("roles");
                    if (roles != null) {
                        for (JsonElement r : roles) {
                            if (requiredRole.equals(r.getAsString())) {
                                hasRole = true;
                                break;
                            }
                        }
                    }
                }
                if (!hasRole) {
                    throw new StatusCodeException(StatusCode.FORBIDDEN, "Missing required role: " + requiredRole);
                }
            }

        } catch (StatusCodeException e) {
            throw e;
        } catch (Exception e) {
            LOGGER.warn("[ADMIN] JWT validation error", e);
            throw new StatusCodeException(StatusCode.UNAUTHORIZED, "Token validation failed");
        }
    }

    private PublicKey resolvePublicKey(String kid) {
        Map<String, PublicKey> keys = jwksCache.get();
        boolean cacheValid = !keys.isEmpty() &&
                Duration.between(jwksCacheTime, Instant.now()).compareTo(JWKS_CACHE_TTL) < 0;

        if (cacheValid) {
            PublicKey key = kid != null ? keys.get(kid) : keys.values().iterator().next();
            if (key != null) return key;
        }

        // Refresh JWKS from Keycloak
        try {
            Map<String, PublicKey> fresh = fetchJwks();
            jwksCache.set(fresh);
            jwksCacheTime = Instant.now();
            if (kid != null) return fresh.get(kid);
            return fresh.isEmpty() ? null : fresh.values().iterator().next();
        } catch (Exception e) {
            LOGGER.error("[ADMIN] Failed to fetch JWKS from Keycloak", e);
            // Fall back to the stale cache rather than locking out all admins during
            // a transient Keycloak outage. Signature verification still runs against
            // the cached keys, so the security boundary is maintained.
            if (!keys.isEmpty()) {
                LOGGER.warn("[ADMIN] Using stale JWKS cache ({} key(s)) after refresh failure", keys.size());
                return kid != null ? keys.get(kid) : keys.values().iterator().next();
            }
            return null;
        }
    }

    private Map<String, PublicKey> fetchJwks() throws Exception {
        String jwksUri = keycloakUrl + "/realms/" + keycloakRealm + "/protocol/openid-connect/certs";
        LOGGER.info("[ADMIN] Fetching JWKS from {}", jwksUri);

        HttpResponse<String> response = httpClient.send(
                HttpRequest.newBuilder().uri(URI.create(jwksUri)).timeout(Duration.ofSeconds(10)).GET().build(),
                HttpResponse.BodyHandlers.ofString()
        );

        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            String preview = response.body().substring(0, Math.min(200, response.body().length()));
            throw new java.io.IOException("JWKS endpoint returned HTTP " + status + ": " + preview);
        }

        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(response.body());
        } catch (Exception e) {
            String preview = response.body().substring(0, Math.min(200, response.body().length()));
            throw new java.io.IOException("JWKS endpoint returned non-JSON response (HTTP " + status + "): " + preview, e);
        }

        if (!parsed.isJsonObject()) {
            throw new java.io.IOException("JWKS response is not a JSON object");
        }

        JsonArray keysArray = parsed.getAsJsonObject().getAsJsonArray("keys");

        Map<String, PublicKey> keyMap = new HashMap<>();
        KeyFactory keyFactory = KeyFactory.getInstance("RSA");
        Base64.Decoder decoder = Base64.getUrlDecoder();

        for (JsonElement elem : keysArray) {
            JsonObject key = elem.getAsJsonObject();
            String kty = key.has("kty") ? key.get("kty").getAsString() : "";
            String use = key.has("use") ? key.get("use").getAsString() : "sig";
            if (!"RSA".equals(kty) || !"sig".equals(use)) continue;

            String keyKid = key.has("kid") ? key.get("kid").getAsString() : "default";
            BigInteger n = new BigInteger(1, decoder.decode(key.get("n").getAsString()));
            BigInteger e = new BigInteger(1, decoder.decode(key.get("e").getAsString()));
            keyMap.put(keyKid, keyFactory.generatePublic(new RSAPublicKeySpec(n, e)));
            LOGGER.debug("[ADMIN] Loaded RSA signing key kid={}", keyKid);
        }

        LOGGER.info("[ADMIN] Loaded {} JWKS key(s)", keyMap.size());
        return keyMap;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private long[] parseSince(String since) {
        long now = System.currentTimeMillis();
        long sinceMillis = switch (since) {
            case "1h" -> now - Duration.ofHours(1).toMillis();
            case "7d" -> now - Duration.ofDays(7).toMillis();
            case "30d" -> now - Duration.ofDays(30).toMillis();
            default -> now - Duration.ofHours(24).toMillis();
        };
        return new long[]{sinceMillis, now};
    }

    private JsonArray eventTypeListToJson(List<Map<String, Object>> rows) {
        JsonArray arr = new JsonArray();
        for (Map<String, Object> row : rows) {
            JsonObject item = new JsonObject();
            item.addProperty("type", String.valueOf(row.get("event_type")));
            item.addProperty("count", ((Number) row.get("count")).longValue());
            arr.add(item);
        }
        return arr;
    }

    private static String padBase64(String base64url) {
        int pad = (4 - base64url.length() % 4) % 4;
        return pad == 0 ? base64url : base64url + "=".repeat(pad);
    }
}
