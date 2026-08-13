CREATE TABLE IF NOT EXISTS bytebin.usage_events (
    id              BIGSERIAL PRIMARY KEY,
    event_type      VARCHAR(32) NOT NULL,       -- 'ui_visit', 'api_post', 'api_get', 'api_put', 'key_visit'
    timestamp       BIGINT NOT NULL,            -- epoch millis
    ip_address      VARCHAR(45),                -- supports IPv6
    user_agent      VARCHAR(1024),
    origin          VARCHAR(1024),
    host            VARCHAR(512),
    referer         VARCHAR(2048),
    accept_language VARCHAR(512),
    content_key     VARCHAR(255),               -- null for UI visits, populated for key visits and API calls
    http_method     VARCHAR(10),
    content_type    VARCHAR(255),               -- content-type of the paste (for POST/PUT)
    content_length  INTEGER,                    -- size of the content body (for POST/PUT)
    content_encoding VARCHAR(255),              -- content-encoding header value
    response_code   INTEGER,                    -- HTTP response status code
    has_api_key     BOOLEAN NOT NULL DEFAULT FALSE,  -- whether the request included a valid API key
    has_custom_expiry BOOLEAN NOT NULL DEFAULT FALSE, -- whether the request used the Bytebin-Expiry header
    has_max_reads   BOOLEAN NOT NULL DEFAULT FALSE,   -- whether the request used Bytebin-Max-Reads header
    has_allow_modification BOOLEAN NOT NULL DEFAULT FALSE, -- whether Allow-Modification was requested
    is_forwarded    BOOLEAN NOT NULL DEFAULT FALSE  -- whether the IP was forwarded via Bytebin-Forwarded-For
);

CREATE INDEX idx_usage_events_event_type ON bytebin.usage_events (event_type);
CREATE INDEX idx_usage_events_timestamp ON bytebin.usage_events (timestamp);
CREATE INDEX idx_usage_events_content_key ON bytebin.usage_events (content_key);
CREATE INDEX idx_usage_events_ip_address ON bytebin.usage_events (ip_address);
