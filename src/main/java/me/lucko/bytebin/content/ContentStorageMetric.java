package me.lucko.bytebin.content;

/**
 * Represents an aggregate metric for content storage, grouped by content type and backend.
 */
public class ContentStorageMetric {
    private String contentType;
    private String backendId;
    private long value;

    public ContentStorageMetric() {
    }

    public String getContentType() {
        return this.contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public String getBackendId() {
        return this.backendId;
    }

    public void setBackendId(String backendId) {
        this.backendId = backendId;
    }

    public long getValue() {
        return this.value;
    }

    public void setValue(long value) {
        this.value = value;
    }
}
