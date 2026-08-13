package me.lucko.bytebin.usage;

/**
 * Represents a single usage event recorded by the application.
 *
 * <p>Events are collected for three categories:</p>
 * <ul>
 *     <li><b>UI visits</b> ({@code ui_visit}) - visits to the web interface (index, docs, etc.)</li>
 *     <li><b>API calls</b> ({@code api_post}, {@code api_get}, {@code api_put}) - programmatic API interactions</li>
 *     <li><b>Key visits</b> ({@code key_visit}) - browser-based visits to view a specific paste</li>
 * </ul>
 */
public final class UsageEvent {

    /** The type of event */
    private String eventType;

    /** The time the event occurred in epoch millis */
    private long timestamp;

    /** The client IP address (may be forwarded) */
    private String ipAddress;

    /** The User-Agent header value */
    private String userAgent;

    /** The Origin header value */
    private String origin;

    /** The Host header value */
    private String host;

    /** The Referer header value */
    private String referer;

    /** The Accept-Language header value */
    private String acceptLanguage;

    /** The content key associated with this event (null for UI visits) */
    private String contentKey;

    /** The HTTP method used */
    private String httpMethod;

    /** The content type of the paste body (for POST/PUT) */
    private String contentType;

    /** The size of the content body in bytes (for POST/PUT) */
    private Integer contentLength;

    /** The content encoding used (for POST/PUT) */
    private String contentEncoding;

    /** The HTTP response status code */
    private Integer responseCode;

    /** Whether the request included a valid API key */
    private boolean hasApiKey;

    /** Whether the request used the custom Bytebin-Expiry header */
    private boolean hasCustomExpiry;

    /** Whether the request used the Bytebin-Max-Reads header */
    private boolean hasMaxReads;

    /** Whether the request used the Allow-Modification header */
    private boolean hasAllowModification;

    /** Whether the IP was forwarded via Bytebin-Forwarded-For */
    private boolean isForwarded;

    public UsageEvent() {
    }

    // Getters and setters

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public void setIpAddress(String ipAddress) {
        this.ipAddress = ipAddress;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public void setUserAgent(String userAgent) {
        this.userAgent = userAgent;
    }

    public String getOrigin() {
        return origin;
    }

    public void setOrigin(String origin) {
        this.origin = origin;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public String getReferer() {
        return referer;
    }

    public void setReferer(String referer) {
        this.referer = referer;
    }

    public String getAcceptLanguage() {
        return acceptLanguage;
    }

    public void setAcceptLanguage(String acceptLanguage) {
        this.acceptLanguage = acceptLanguage;
    }

    public String getContentKey() {
        return contentKey;
    }

    public void setContentKey(String contentKey) {
        this.contentKey = contentKey;
    }

    public String getHttpMethod() {
        return httpMethod;
    }

    public void setHttpMethod(String httpMethod) {
        this.httpMethod = httpMethod;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public Integer getContentLength() {
        return contentLength;
    }

    public void setContentLength(Integer contentLength) {
        this.contentLength = contentLength;
    }

    public String getContentEncoding() {
        return contentEncoding;
    }

    public void setContentEncoding(String contentEncoding) {
        this.contentEncoding = contentEncoding;
    }

    public Integer getResponseCode() {
        return responseCode;
    }

    public void setResponseCode(Integer responseCode) {
        this.responseCode = responseCode;
    }

    public boolean isHasApiKey() {
        return hasApiKey;
    }

    public void setHasApiKey(boolean hasApiKey) {
        this.hasApiKey = hasApiKey;
    }

    public boolean isHasCustomExpiry() {
        return hasCustomExpiry;
    }

    public void setHasCustomExpiry(boolean hasCustomExpiry) {
        this.hasCustomExpiry = hasCustomExpiry;
    }

    public boolean isHasMaxReads() {
        return hasMaxReads;
    }

    public void setHasMaxReads(boolean hasMaxReads) {
        this.hasMaxReads = hasMaxReads;
    }

    public boolean isHasAllowModification() {
        return hasAllowModification;
    }

    public void setHasAllowModification(boolean hasAllowModification) {
        this.hasAllowModification = hasAllowModification;
    }

    public boolean isForwarded() {
        return isForwarded;
    }

    public void setForwarded(boolean forwarded) {
        isForwarded = forwarded;
    }

    /**
     * Creates a builder for constructing UsageEvent instances.
     *
     * @param eventType the event type
     * @return a new builder
     */
    public static Builder builder(String eventType) {
        return new Builder(eventType);
    }

    /**
     * Fluent builder for {@link UsageEvent}.
     */
    public static final class Builder {
        private final UsageEvent event;

        private Builder(String eventType) {
            this.event = new UsageEvent();
            this.event.setEventType(eventType);
            this.event.setTimestamp(System.currentTimeMillis());
        }

        public Builder ipAddress(String ipAddress) {
            this.event.setIpAddress(ipAddress);
            return this;
        }

        public Builder userAgent(String userAgent) {
            this.event.setUserAgent(userAgent);
            return this;
        }

        public Builder origin(String origin) {
            this.event.setOrigin("null".equals(origin) ? null : origin);
            return this;
        }

        public Builder host(String host) {
            this.event.setHost(host);
            return this;
        }

        public Builder referer(String referer) {
            this.event.setReferer(referer);
            return this;
        }

        public Builder acceptLanguage(String acceptLanguage) {
            this.event.setAcceptLanguage(acceptLanguage);
            return this;
        }

        public Builder contentKey(String contentKey) {
            this.event.setContentKey(contentKey);
            return this;
        }

        public Builder httpMethod(String httpMethod) {
            this.event.setHttpMethod(httpMethod);
            return this;
        }

        public Builder contentType(String contentType) {
            this.event.setContentType(contentType);
            return this;
        }

        public Builder contentLength(Integer contentLength) {
            this.event.setContentLength(contentLength);
            return this;
        }

        public Builder contentEncoding(String contentEncoding) {
            this.event.setContentEncoding(contentEncoding);
            return this;
        }

        public Builder responseCode(Integer responseCode) {
            this.event.setResponseCode(responseCode);
            return this;
        }

        public Builder hasApiKey(boolean hasApiKey) {
            this.event.setHasApiKey(hasApiKey);
            return this;
        }

        public Builder hasCustomExpiry(boolean hasCustomExpiry) {
            this.event.setHasCustomExpiry(hasCustomExpiry);
            return this;
        }

        public Builder hasMaxReads(boolean hasMaxReads) {
            this.event.setHasMaxReads(hasMaxReads);
            return this;
        }

        public Builder hasAllowModification(boolean hasAllowModification) {
            this.event.setHasAllowModification(hasAllowModification);
            return this;
        }

        public Builder forwarded(boolean forwarded) {
            this.event.setForwarded(forwarded);
            return this;
        }

        public UsageEvent build() {
            return this.event;
        }
    }
}
