CREATE TABLE IF NOT EXISTS content (
    key             VARCHAR(255) PRIMARY KEY,
    content_type    VARCHAR(255),
    expiry          BIGINT,
    last_modified   BIGINT NOT NULL DEFAULT 0,
    encoding        VARCHAR(255),
    backend_id      VARCHAR(255),
    content_length  INTEGER NOT NULL DEFAULT 0,
    max_reads       INTEGER NOT NULL DEFAULT -1,
    read_count      INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_content_content_type ON content (content_type);
CREATE INDEX IF NOT EXISTS idx_content_expiry ON content (expiry);
